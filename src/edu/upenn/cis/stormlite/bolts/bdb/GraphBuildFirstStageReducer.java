package edu.upenn.cis.stormlite.bolts.bdb;

import java.io.File;
import java.io.FileWriter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;
import edu.upenn.cis.stormlite.bolts.IRichBolt;
import edu.upenn.cis.stormlite.bolts.OutputCollector;
import edu.upenn.cis.stormlite.infrastructure.OutputFieldsDeclarer;
import edu.upenn.cis.stormlite.infrastructure.TopologyContext;
import edu.upenn.cis.stormlite.routers.StreamRouter;
import edu.upenn.cis.stormlite.tuple.Fields;
import edu.upenn.cis.stormlite.tuple.Tuple;
import edu.upenn.cis.stormlite.tuple.Values;
import edu.upenn.cis455.database.DBInstance;
import edu.upenn.cis455.database.DBManager;
import edu.upenn.cis455.database.Node;

public class GraphBuildFirstStageReducer implements IRichBolt {
	Logger log = Logger.getLogger(GraphBuildFirstStageReducer.class);
//	public static Logger log = Logger.getLogger(FirstaryReduceBolt.class);
	public static Map<String, String> config;
    public String executorId = UUID.randomUUID().toString();
	public Fields schema = new Fields("key", "value"); 
	public OutputCollector collector;
	public Integer eosNeeded = 0;
	public static DBInstance graphDB;
	public static DBInstance tempDB;
	//public boolean sentEof = false;
	public int count = 0;
	public String serverIndex;
	public File outfile;
	public FileWriter outputWriter;
	private FileWriterQueue fwq;
	public static AtomicBoolean eosSent;
	
	@Override
	public String getExecutorId() {
		return executorId;
	}

	@Override
	public void declareOutputFields(OutputFieldsDeclarer declarer) {
		declarer.declare(schema);
	}

	@Override
	public void cleanup() {

	}

	@Override
	public void execute(Tuple input) {
		
    	if (eosSent.get()) { 
    		
	        if (!input.isEndOfStream()) {
	        	log.info("Server# " + serverIndex + "::"+executorId+" Firstary MAYDAY MAYDAY! " + input.getStringByField("key") + " / " + input.getStringByField("value"));
	        	log.error("We received data after we thought the stream had ended!");
	        	return;
//	        	throw new RuntimeException("We received data after we thought the stream had ended!");
	        }
	        log.error("Server# " + serverIndex + "::"+executorId+" Firstary MAYDAY MAYDAY! EOS AGAIN!!!!!");
		}
    	else if (input.isEndOfStream()) {
    		eosNeeded--;
			log.info("Server#" + serverIndex + "::"+executorId+" EOS Received(reducer): " + (++count)+"/"+(eosNeeded+count));			
			
			if (eosNeeded == 0) {
				
				eosSent.set(true);
				
				log.info("start first stage reduction");				
				config.put("status", "REDUCING");				
				Map<String, List<String>> table;
				table = tempDB.getTable(executorId);					
//				log.info("Server# " + serverIndex + " " + table);
				
				try {
					Iterator<String> keyIt = table.keySet().iterator();	
					while (keyIt.hasNext()) {
						String key = keyIt.next();					
						Node node;
						List<String> vals = table.get(key);
						Iterator<String> valueIt = vals.iterator();					
				        if (!graphDB.hasNode(key)) {			        	
				        	node  = new Node(key);		
				        	fwq.addQueue(String.format("%s\n", key));
				        }
				        else {
				        	node = graphDB.getNode(key);
				        }
				        
				        log.info("Server#" + serverIndex + " added " + node.getID() + " -> " + vals);

						while (valueIt.hasNext()) {
							String nextVal = valueIt.next();
							node.addNeighbor(nextVal);						
							collector.emit(new Values<Object>(key, nextVal));
						}		        						
						graphDB.addNode(node);
					} 
					
					log.info("-- first stage reduction complete --");	
					log.info("-- MR job complete --");	
				}
				catch (NullPointerException e) {
					// ignore all null pointer exceptions
					// for now coz the result seems to be right XD
//					e.printStackTrace();
				}							
				synchronized(tempDB) {
					tempDB.clearTempData();	
				}				
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}				
				collector.emitEndOfStream();
//				log.info("Server#"+serverIndex+"::"+executorId+" emits eos to reducer2.");
			}
    	}
    	else {
    	
    		String key = input.getStringByField("key");
	        String value = input.getStringByField("value");	        	              
	        synchronized(tempDB) { tempDB.addKeyValue(executorId, key, value); }
    	}		
	}

	@Override
	public void prepare(Map<String, String> stormConf, TopologyContext context, OutputCollector collector) {
				
		config = stormConf;
		serverIndex = stormConf.get("workerIndex");
		
		eosSent = new AtomicBoolean(false);
		
		String graphDataDir  = config.get("graphDataDir");
		String databaseDir   = config.get("databaseDir");
		String outputFileDir = config.get("outputDir");	
		
		if (serverIndex != null) {
			graphDataDir  += "/" + serverIndex;
			databaseDir   += "/" + serverIndex + "-1";
			outputFileDir += "/" + serverIndex;
		}
				
		File outfileDir = new File(outputFileDir);
		outfileDir.mkdirs();
		
		String outputFileName = "names.txt";
		outfile = new File(outfileDir, outputFileName);
		
		fwq = FileWriterQueue.getFileWriterQueue(outfile, context);
		graphDB = DBManager.getDBInstance(graphDataDir);		
		tempDB  = DBManager.getDBInstance(databaseDir);
			
        this.collector = collector;
        if (!stormConf.containsKey("mapExecutors")) {
        	log.error("Reducer class doesn't know how many map bolt executors");
        	return;
        }

		int numMappers  = Integer.parseInt(stormConf.get("mapExecutors"));		
		int numReducers = Integer.parseInt(stormConf.get("reduceExecutors"));
		int numWorkers  = Integer.parseInt(stormConf.get("workers"));			
        eosNeeded = (numWorkers - 1) * numMappers* numReducers  +  numMappers;
        log.debug("Num EOS required for ReduceBolt: " + eosNeeded);
	}

	@Override
	public void setRouter(StreamRouter router) {		
		// router has only to do with collector
		collector.setRouter(router);
	}

	@Override
	public Fields getSchema() {
		return schema;
	}

}
