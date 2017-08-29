import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function2;

import scala.Tuple2;

public class DDS {
	 
	private static double mean;
	private static double covariance;
	final private static double totalCells=31.0*40.0*55.0;
	public static void main(String args[]) throws Exception{
		
		//	Initial spark configuration boilerplate
		SparkConf conf = new SparkConf().setAppName("org.sparkexample.GetisOrd").setMaster("local[*]");
	    conf.set("spark.driver.allowMultipleContexts", "true", true);
		JavaSparkContext sc = new JavaSparkContext(conf);
	    
	    //	Extracting file from hdfs
		JavaRDD<String> inputRDD = sc.textFile(args[0]);
		
		//	Filtering out the string header
		String header=inputRDD.first();
		inputRDD=inputRDD.filter(e -> { return !e.equalsIgnoreCase(header); });
		// THIS WORKED System.out.println("Full total="+inputRDD.count());
		
		
        //	Transforming input RDD to RDD with Date, Latitude and Longitude
		JavaRDD<String> cleanedRDD=transformAndCleanInputRDD(inputRDD);
        
        // THIS WORKED System.out.println("Total rows="+cleanedRDD.count());
        
        //	Filtering out the nulls
        cleanedRDD=cleanedRDD.filter( e -> { return !e.equalsIgnoreCase("null"); });
        //	THIS WORKED System.out.println("Total rows after filtering null="+cleanedRDD.count());

        //	Creating a JavaPairRDD consisting of Key(ie: Day,Latitude,Longitude) and Value(ie: Corresponding number of pick ups for the key)
        JavaPairRDD<String,Long> mapRDD=cleanedRDD.mapToPair(key -> new Tuple2<>(key,1l)).reduceByKey((x,y)->x+y);              
        // THIS WORKED System.out.println("Final rows after mapping and filtering="+mapRDD.count());

        //	Calculate mean, covariance, numerator and denominator
        calculateMeanCovariance(mapRDD);
        //	THIS WORKED System.out.println("Mean="+mean+"\tCovariance="+covariance);
      
        Map<String, Double> cube=populateCube(mapRDD);
        
        //	Sort map
        Map<String,Double> sortedMap=sortMap(cube);
        List<String> outputDataList = new ArrayList<String>();;
        //	Storing in HDFS
        int count=1;
        for(Entry<String,Double> e:sortedMap.entrySet()){
        	String str = e.getKey();
        	int day=Integer.valueOf(str.split(",")[0]);
			int latitude=Integer.valueOf(str.split(",")[1]);
			double dlat = latitude/100.00;
			int longitude=Integer.valueOf(str.split(",")[2]);
			double dlong = longitude/100.00;
			String s=String.valueOf(dlat)+","+String.valueOf(dlong)+","+String.valueOf(day);
			outputDataList.add(s+","+e.getValue());
        	//System.out.println(s+":"+e.getValue());
        	if(++count>50) break;
        }   
        
        //	Storing into HDFS
        JavaRDD<String> outputDataRDD = sc.parallelize(outputDataList);
        outputDataRDD.saveAsTextFile(args[1]);
 	}

	private static Map<String, Double> sortMap(Map<String, Double> cube) {
		List<Entry<String, Double>> list= new LinkedList<Entry<String, Double>>(cube.entrySet());
		Collections.sort(list, new Comparator<Map.Entry<String,Double>>(){

			@Override
			public int compare(Entry<String, Double> o1,Entry<String, Double> o2) {
				return o2.getValue().compareTo(o1.getValue());
			}
		});
		
		Map<String,Double> result=new LinkedHashMap<String,Double>();
		for(Entry<String,Double> entry:list){
			result.put(entry.getKey(), entry.getValue());
		}
		return result;
		
	}

	
	private static Map<String, Double> populateCube(JavaPairRDD<String, Long> mapRDD) {
		Map<String,Long> map=mapRDD.collectAsMap();
		
		double date,latitude,longitude,value;
		int i,j,k;
		HashMap<String,Double> cube=new HashMap<String,Double>();
		String str;
		//latitude 40.5N – 40.9N, longitude 73.7W – 74.25W
			for(i=0;i<=30;i++){	//	Date
				for(j=4050;j<=4090;j=j+1){	//	Latitude
					//System.out.println("jvalr:"+j);
					for(k=-7425;k<=-7370;k=k+1){	//	Longitude
					
						str=String.valueOf(i)+","+String.valueOf(j)+","+String.valueOf(k);
						
						if(map.containsKey(str)){
							value=computeGetisOrd(str, map);
							cube.put(str, value);
						}
					}
				}
			}
		return cube;
	}
	
	private static void calculateMeanCovariance(JavaPairRDD<String, Long> mapRDD) {

		long count=mapRDD.values().reduce(new Function2<Long,Long,Long>(){
			 @Override
		      public Long call(Long a, Long b) {
		        return a + b;
		      }
		});
		//long n=mapRDD.count();
		double n=totalCells;
		mean=(double)count/n;
		long sum;
		JavaRDD<Long> squaredRDD=mapRDD.map(e -> {
			return (long)e._2*e._2;
		});
		sum=squaredRDD.reduce((a,b) -> { return a+b; });
		//	THIS WORKED System.out.println("SUM="+sum+"Val="+((double)sum/(double)n)+":\tMath.pow="+Math.pow(mean,2.0));
		covariance=Math.sqrt(((double)sum/(double)n) - Math.pow(mean,2.0));
		
	}
	
	private static JavaRDD<String> transformAndCleanInputRDD(JavaRDD<String> inputRDD){
		SimpleDateFormat formatter=new SimpleDateFormat("yyyy-MM-dd");
		return inputRDD.map(e -> {
			String dateString="";
			try{			
				String strs[]=e.split(",");
				//        	Rounding off to two decimal places
				int longitude=(int)Math.floor(Double.parseDouble(strs[5].trim())*100);
				int latitude=(int)Math.floor(Double.parseDouble(strs[6].trim())*100);			
				dateString=strs[1].split(" ")[0];
				Date date=formatter.parse(dateString);	
				e= String.valueOf(date.getDate()-1)+","+String.valueOf(latitude)+","+String.valueOf(longitude);
				if(latitude<4050 || latitude>4090 || longitude<-7425 || longitude>-7370){
					//System.out.println("latitude="+latitude+"\tlongitude:"+longitude);
					return "null";
				}
				else{
					return e;
				}
			}
			catch(Exception s){
				System.out.println(dateString);
				s.printStackTrace();
				return "null";
			}
        });
	}
	
	private static double computeGetisOrd(String str, Map<String, Long> cubeMap) {
		double getisOrd=0;
		int i,j,k,day;
		int n[]={-1,0,1},latitude,longitude;double sum = 0;
		String string;
		long count=0, square=0;
		sum=0.0;
		for(i=-1;i<=1;i++){
			for(int dLat:n){
				for(int dLong:n){
					day=Integer.valueOf(str.split(",")[0]);
					latitude=Integer.valueOf(str.split(",")[1]);
					longitude=Integer.valueOf(str.split(",")[2]);
					string=String.valueOf(day+i)+","+String.valueOf(latitude+dLat)+","+String.valueOf(longitude+dLong);
					if(cubeMap.containsKey(string)){
						count++;
						sum+=cubeMap.get(string);
					}
				}
			}
		}
		double sqrtNumerator=(totalCells*count) - Math.pow(count, 2);
		double sqrtDenominator=totalCells-1;
		double sqrt=Math.sqrt(sqrtNumerator/sqrtDenominator);
		double numerator=count*mean;
		double denominator=covariance * sqrt;
		getisOrd=(sum-numerator)/denominator;		
		return getisOrd;
	}
	
}
