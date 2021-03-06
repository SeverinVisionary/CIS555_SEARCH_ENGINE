package edu.upenn.cis.stormlite.spouts.pagerank;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.UUID;
import org.apache.log4j.Logger;
import edu.upenn.cis.stormlite.infrastructure.OutputFieldsDeclarer;
import edu.upenn.cis.stormlite.infrastructure.TopologyContext;
import edu.upenn.cis.stormlite.routers.StreamRouter;
import edu.upenn.cis.stormlite.spouts.IRichSpout;
import edu.upenn.cis.stormlite.spouts.SpoutOutputCollector;
import edu.upenn.cis.stormlite.tuple.Fields;
import edu.upenn.cis.stormlite.tuple.Values;
import edu.upenn.cis455.database.DBInstance;
import edu.upenn.cis455.database.DBManager;
import edu.upenn.cis455.database.Node;

public class RankDataSpout implements IRichSpout {
	
	public static Logger log = Logger.getLogger(RankDataSpout.class);
	public String executorId = UUID.randomUUID().toString();
	public String filename;
	public Random r = new Random();
	public SpoutOutputCollector collector;
	public Fields schema = new Fields("key", "value");	
	@SuppressWarnings("rawtypes")
	public Map config;
	public boolean eofSent = false;	
	private DBInstance nodesStore;
	private String databaseDir;
	private File sourceUrlFile;
	private Scanner scanner;
	public String serverIndex = null;

	public RankDataSpout() {
		
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
    public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
		
        this.collector = collector;
        config = conf;
        config.put("status", "IDLE");
        databaseDir = (String)conf.get("graphDataDir");       
        serverIndex = (String)conf.get("workerIndex");
        String urlFileDir = (String)conf.get("inputDir");
        
        String targetDirectory = databaseDir;
        if (serverIndex != null) {
        	targetDirectory += "/" + serverIndex;
        	urlFileDir += "/" + serverIndex;
        }
		nodesStore = DBManager.getDBInstance(targetDirectory);		

		sourceUrlFile = new File(urlFileDir, "names.txt");
		
		try  {
			scanner = new Scanner(sourceUrlFile);
			if (!scanner.hasNext()) {
				throw new IllegalStateException();
			}
			
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		
    }
	
	@Override
	public void close() {
		if (scanner != null) {
			if (eofSent) {
				scanner.close();
				scanner = null;
				System.out.println("File reader instance has been closed");
			}
		}
	}
	
	@Override
	public synchronized void nextTuple() {
		
		if (scanner != null && !eofSent) {
			try {
				
				String urlName = null;
				if (scanner.hasNext()) urlName = scanner.nextLine();
				
				if (urlName != null && urlName.length() > 0) {
					
					Node nextNode = nodesStore.getNode(urlName);					
					Iterator<String> neighborIt = nextNode.getNeighborsIterator();				
					int numNeighbors = nextNode.getNumberNeighbors();
					double rank = nextNode.getRank();
					double averageWeight = rank / numNeighbors;
					while (neighborIt.hasNext()) {
						String thisID = nextNode.getID();
						String neighborID = neighborIt.next();
						collector.emit(new Values<Object>(thisID, neighborID + ":" + averageWeight));				
					}
				}
				else if (!eofSent) {
					
					log.info("Finished reading data at " + databaseDir + " and emitting EOS");
					collector.emitEndOfStream();
					eofSent = true;
				}				
			} 
			catch (Exception ioe) {
				ioe.printStackTrace();
			}
		}
		Thread.yield();
	}
	
	@Override
	public void declareOutputFields(OutputFieldsDeclarer declarer) {	
		declarer.declare(schema);
	}
	
	@Override
	public String getExecutorId() {
		return executorId;
	}
	
	@Override
	public void setRouter(StreamRouter router) {
		collector.setRouter(router);
	}
}
