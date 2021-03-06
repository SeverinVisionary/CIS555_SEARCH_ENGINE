package edu.upenn.cis455.stormlite.tests;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Arrays;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.upenn.cis.stormlite.bolts.bdb.BuilderMapBolt;
import edu.upenn.cis.stormlite.bolts.bdb.GraphBuildFirstStageReducer;
import edu.upenn.cis.stormlite.bolts.bdb.GraphBuildSecondStageReducer;
import edu.upenn.cis.stormlite.infrastructure.Configuration;
import edu.upenn.cis.stormlite.infrastructure.Topology;
import edu.upenn.cis.stormlite.infrastructure.TopologyBuilder;
import edu.upenn.cis.stormlite.infrastructure.WorkerJob;
import edu.upenn.cis.stormlite.spouts.bdb.LinksFileSpout;
import edu.upenn.cis.stormlite.spouts.bdb.LinksSpout;
import edu.upenn.cis.stormlite.tuple.Fields;
import edu.upenn.cis455.mapreduce.servlets.MasterServlet;

public class TestInitializeDistributedDatabase {
	
	private static final String SPOUT       = "LINK_SPOUT";
	private static final String MAP_BOLT    = "MAP_BOLT";
	private static final String REDUCE_BOLT = "REDUCE_BOLT";
	private static final String WRAPPER_BOLT  = "FIXER";
	
	public static void main(String[] args) throws IOException, InterruptedException {
		int numMappers  = 1;
		int numSpouts   = 1;
		int numFirstReducers = 1;
		int numSecondaryReducers = 1;
		
		String inputDir  = "test_data" ; 
		String outputDir = "urls";
		String jobName   = "job1"; 
				
		LinksFileSpout spout     = new LinksSpout();		
	    BuilderMapBolt mapBolt   = new BuilderMapBolt();
	    GraphBuildFirstStageReducer reduceBolt = new GraphBuildFirstStageReducer();
	    GraphBuildSecondStageReducer postBolt = new GraphBuildSecondStageReducer();
	    
	    // build topology
		TopologyBuilder builder = new TopologyBuilder();			    			    
        builder.setSpout(SPOUT, spout, numSpouts);
        builder.setBolt(MAP_BOLT, mapBolt, numMappers).shuffleGrouping(SPOUT);		        
        builder.setBolt(REDUCE_BOLT, reduceBolt, numFirstReducers).fieldsGrouping(MAP_BOLT, new Fields("key"));
        builder.setBolt(WRAPPER_BOLT, postBolt, numSecondaryReducers).fieldsGrouping(REDUCE_BOLT, new Fields("value"));
        
        Topology topo = builder.createTopology();
        
        // create configuration object
        Configuration config = new Configuration();        
        config.put("spoutExecutors",   (new Integer(numSpouts)).toString());
        config.put("mapExecutors",     (new Integer(numMappers)).toString());
        config.put("reduceExecutors",  (new Integer(numFirstReducers)).toString());
        config.put("reduce2Executors", (new Integer(numSecondaryReducers)).toString());
        config.put("inputDir", inputDir);
        config.put("outputDir", outputDir);
        config.put("job", jobName);       
        config.put("workers", "2");
        config.put("graphDataDir", "graphStore");
        config.put("databaseDir" , "storage");
        config.put("status", "IDLE");
        config.put("numThreads", "2");
       
        WorkerJob job = new WorkerJob(topo, config);
        ObjectMapper mapper = new ObjectMapper();	        
        mapper.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);        
        String[] workersList = new String[]{"127.0.0.1:8000", "127.0.0.1:8001"};          
        config.put("workerList", Arrays.toString(workersList));		        
        
		try {
			int j = 0;
			for (String dest: workersList) {
		        config.put("workerIndex", String.valueOf(j++));
				if (MasterServlet.sendJob(dest, "POST", config, "definejob", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(job)).getResponseCode() != HttpURLConnection.HTTP_OK) {					
					throw new RuntimeException("Job definition request failed");
				}
			}
			for (String dest: workersList) {
				if (MasterServlet.sendJob(dest, "POST", config, "runjob", "").getResponseCode() != HttpURLConnection.HTTP_OK) {						
					throw new RuntimeException("Job execution request failed");
				}
			}
		}
		catch (JsonProcessingException e) {
			e.printStackTrace();
		}
		
		Thread.sleep(5000);
		System.exit(0);
	}
}
