
import com.nineteendrops.tracdrops.client.Trac;
import com.nineteendrops.tracdrops.client.api.ticket.ticket.TicketManager;
import com.nineteendrops.tracdrops.client.api.ticket.ticket.TicketQuery;
import com.nineteendrops.tracdrops.client.api.ticket.ticket.TicketQueryFilter;
import com.nineteendrops.tracdrops.client.api.ticket.ticket.Ticket;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Date;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;

public class TracMetrics extends TimerTask {
	
	private class OwnerData {
		public String name;
		// currently open tickets assigned to owner
		public int numOpen;
		// Outgoing Tickets for this owner in the last period
		public int numClosed;
		public int numDeferred;
		public int numReassignedFrom;
		// incoming tickets for this owner in the last period
		public int numNew;
		public int numReopened;
		public int numReassignedTo;
		
		public OwnerData(String n)
		{
			name = n;
			numOpen = 0;
			numClosed = 0;
			numDeferred = 0;
			numReassignedFrom = 0;
			numNew = 0;
			numReopened = 0;
			numReassignedTo = 0;
		}
	}
	
	private class MyTicket {
		private int ticketId;
		private String owner;
		private String status;
		
		public MyTicket(int tid, String o, String s) {
			ticketId = tid;
			owner = o;
			status = s;
		}
		
		public int getTicketId() { return ticketId; }
		public String getOwner() { return owner; }
		public String getStatus() { return status; }
	}
	
	private class Snapshot {
		private ArrayList<MyTicket> tickets = null;
		private java.sql.Timestamp date = null;
		private TreeMap<String,OwnerData> ownerDataMap = null;

		public Snapshot(java.sql.Timestamp ts) {
			tickets = new ArrayList<MyTicket>();
			date = ts;
			ownerDataMap = new TreeMap<String,OwnerData>();
		}
	}
	
	private class MyQuery {
		private int qid;
		private String name;
		private TicketQuery query;
		
		public MyQuery(int id, String n, TicketQuery q) {
			qid = id;
			name = n;
			query = q;
		}
	}
	
	private class QueryResults {
		private ArrayList<Snapshot> prevSnapshots = null;
		private Snapshot currSnapshot = null;
		
		public QueryResults() {
		}
	}
	
	private final static int SNAPSHOT_INTERVAL = 15;
	private ArrayList<MyQuery> queryList;
	private ArrayList<QueryResults> resultsList;

    public static void main(String[] args) throws Exception{
        TracMetrics timerTask = new TracMetrics();
        timerTask.initQueryList();
        // init prevOwnerData from the database
        timerTask.initPrevOwnerData();
        
        // running timer task as daemon thread
        Timer timer = new Timer();
        // schedule task to be run every SNAPSHOT_INTERVAL minutes
        timer.scheduleAtFixedRate(timerTask, 0, SNAPSHOT_INTERVAL*60*1000);
    }
    
    private void initQueryList() {
    	// initialize query list
    	// Make sure to not reuse query id for old non-active queries
    	// if you want to remove a query, set the TicketQuery part to be null
    	// e.g. MyQuery myQ3 = new MyQuery( 3, "removed cedar query", null );
    	// 0th query is dummy
    	queryList = new ArrayList<MyQuery>();
    	MyQuery myQ0 = new MyQuery( 0, "dummy", null);
    	queryList.add(myQ0);
    	
		TicketQueryFilter f1 = new TicketQueryFilter("max", "=", "0");
		TicketQueryFilter f2 = new TicketQueryFilter("status", "!=", "verifying");
		TicketQueryFilter f3 = new TicketQueryFilter("status", "!=", "closed");
		TicketQueryFilter f4 = new TicketQueryFilter("keywords", "~=", "MUSTFIXFOR7.5");
		TicketQueryFilter f5 = new TicketQueryFilter("milestone", "=", "7.5");
		TicketQuery q = new TicketQuery();
		q.addFilter(f1);
		q.addFilter(f2);
		q.addFilter(f3);
		q.addFilter(f4);
		q.addFilter(f5);
    	MyQuery myQ1 = new MyQuery( 1, "cedar", q);
    	queryList.add(myQ1);
    	
    	// We restarted tracMetrics server. see if we added any new queries that are not in gemfire XD
    	try {

    		// 1527 is the default port that a GemFire XD server uses to listen for thin client connections
    		java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:gemfirexd://localhost:1527/");

    		int maxid = 0;
    		java.sql.PreparedStatement pss = conn.prepareStatement("select * from queries where id = (select max(id) from queries)");
    		java.sql.ResultSet rss = pss.executeQuery();
    		if (rss != null && rss.next())
    		    maxid = rss.getInt(1);
    		
    		java.sql.PreparedStatement pst = conn.prepareStatement("INSERT INTO QUERIES VALUES (?, ?)");
    		for(MyQuery myQ: queryList) {
    			if( myQ.query == null ) {
    				// this query was removed
    				continue;
    			}
    			if( myQ.qid <= maxid ) {
    				// this query already exists
    				continue;
    			}
    			// insert this query data
    			pst.clearParameters();
    			pst.setInt(1, myQ.qid);
    			pst.setString(2, myQ.name);
    			pst.executeUpdate();
    		}
     		conn.commit();
    	}
    	catch (java.sql.SQLException ex) {
    		// handle any errors
    		System.out.println("SQLException: " + ex.getMessage());
    		System.out.println("SQLState: " + ex.getSQLState());
    		System.out.println("VendorError: " + ex.getErrorCode());
    	}
    	
    	// initialize the resultsList
    	resultsList = new ArrayList<QueryResults>();
    	for(int i=0; i<queryList.size(); i++)
    		resultsList.add(null);
    }
    
    private void initPrevOwnerData() {
    	// We restarted tracMetrics server. read from gemfire XD
    	// if a previous snapshot can be read then immediately write a new snapshot
    	try {

    		// 1527 is the default port that a GemFire XD server uses to listen for thin client connections
    		java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:gemfirexd://localhost:1527/");

    		java.sql.PreparedStatement pss = conn.prepareStatement("select * from snapshots where id = (select max(id) from snapshots)");
    		java.sql.ResultSet rss = pss.executeQuery();
    		if (rss != null && rss.next()) {
    		    int maxid = rss.getInt(1);
    		    java.sql.Timestamp prevT = rss.getTimestamp(2);
    		
    		    for(MyQuery q: queryList) {
    		    	if( q.query == null ) {
    		    		// this query was removed
    		    		continue;
    		    	}
    		    	java.sql.PreparedStatement ps = conn.prepareStatement("select * from tickets where sid = ? and qid = ?");
    		    	ps.setInt(1, maxid);
    		    	ps.setInt(2, q.qid);
    		    	java.sql.ResultSet rs = ps.executeQuery();
    		    	if (rs != null) {
    		    		QueryResults qr = resultsList.get(q.qid);
    		    		if( qr == null ) {
    		    			qr = new QueryResults();
    		    			resultsList.set(q.qid, qr);
    		    		}
    		    		if( qr.prevSnapshots == null )
    		    			qr.prevSnapshots = new ArrayList<Snapshot>();
        		    	Snapshot aSnapshot = new Snapshot(prevT);
        		    	qr.prevSnapshots.add(aSnapshot);
    		    		while(rs.next()) {
    		    			// int sid = rs.getInt(1);
    		    			// int qid = rs.getInt(2);
    		    			int tid = rs.getInt(3);
    		    			String owner = rs.getString(4);
    		    			String status = rs.getString(5);
    		    			MyTicket t = new MyTicket(tid, owner, status);
    		    			aSnapshot.tickets.add(t);
    		    		}

    		    		java.sql.PreparedStatement psod = conn.prepareStatement("select * from ownerdata where sid = ? and qid = ?");
    		    		psod.setInt(1, maxid);
    		    		psod.setInt(2, q.qid);
    		    		java.sql.ResultSet rsod = psod.executeQuery();
    		    		if (rsod != null) {
    		    			while(rsod.next()) {
    		    				// int sid = rsod.getInt(1);
    		    				// int qid = rsod.getInt(2);
    		    				String name = rsod.getString(3);
    		    				OwnerData od = new OwnerData(name);
    		    				od.numOpen = rsod.getInt(4);
    		    				od.numClosed = rsod.getInt(5);
    		    				od.numDeferred = rsod.getInt(6);
    		    				od.numReassignedFrom = rsod.getInt(7);
    		    				od.numNew = rsod.getInt(8);
    		    				od.numReopened = rsod.getInt(9);
    		    				od.numReassignedTo = rsod.getInt(10);
    		    				aSnapshot.ownerDataMap.put(name, od);
    		    			}
    		    		}
    		    	}
    		    }
    		}
     		conn.commit();
    	}
    	catch (java.sql.SQLException ex) {
    		// handle any errors
    		System.out.println("SQLException: " + ex.getMessage());
    		System.out.println("SQLState: " + ex.getSQLState());
    		System.out.println("VendorError: " + ex.getErrorCode());
    	}
    }
    
    public void run() {
    	try {
    		Trac trac = new Trac( "https://svn.gemstone.com/trac/gemfire/login/xmlrpc", "vjoshi", "p1v0tal" );
    		trac.initialize();
    		TicketManager ticketManager = trac.getTicketManager();
    		java.sql.Timestamp currT = new java.sql.Timestamp(System.currentTimeMillis());

    		for( MyQuery myQ: queryList ) {
    			if( myQ.query == null ) {
    				// this query was removed, nothing to do
    				continue;
    			}
    			
	    		QueryResults qr = resultsList.get(myQ.qid);
	    		if( qr == null ) {
	    			qr = new QueryResults();
	    			resultsList.set(myQ.qid, qr);
	    		}
    			qr.currSnapshot = new Snapshot(currT);
    			ArrayList<Integer> results = ticketManager.query(myQ.query);

    			for( Integer tid: results ) {
    				Ticket t = ticketManager.get(tid);
    				MyTicket mt = new MyTicket(t.getIdTicket(), t.getOwner(), t.getStatus());
    				qr.currSnapshot.tickets.add(mt);
    				String name = t.getOwner();
    				OwnerData o = qr.currSnapshot.ownerDataMap.get(name);
    				if( o == null )
    				{
    					o = new OwnerData(name);
    					qr.currSnapshot.ownerDataMap.put(name,o);
    				}
    				o.numOpen++;
    			}
    		}

    		updateOwnerData(ticketManager);
    		// if the criteria for writing matches then write data
    		if( writeCriteriaMatch(currT) ) {
    			combineSnapshots();
    			writeOwnerData(currT);
    			// reset the prevSnapshots
    			for(QueryResults qr: resultsList)
    				if( qr != null )
    					qr.prevSnapshots = new ArrayList<Snapshot>();
    		}
			for(QueryResults qr: resultsList)
				if( qr != null ) {
					if( qr.prevSnapshots == null )
						qr.prevSnapshots = new ArrayList<Snapshot>();
					qr.prevSnapshots.add(qr.currSnapshot);
					qr.currSnapshot = null;
				}
    	}
    	catch( RuntimeException e ) {
    		// ignore any RuntimeException thrown when connecting to Trac
    	}
    }
    
    @SuppressWarnings("deprecation")
	private boolean writeCriteriaMatch(java.sql.Timestamp currT) {
    	int[] hourArray = { 0, 6, 12, 18 };
    	int[] minArray = {0};
    	int hour = currT.getHours();
    	int min = currT.getMinutes();
    	
    	for(int h: hourArray) {
    		if( hour == h ) {
    			for(int m: minArray) {
    				if(min >= m && min < m+SNAPSHOT_INTERVAL)
    					return true;
    			}
    		}
    	}
    	return false;
    }
    
    private void updateOwnerData(TicketManager tm) {
		for(QueryResults qr: resultsList) {
			if( qr != null ) {
				if( qr.prevSnapshots == null || qr.prevSnapshots.size() == 0 ) {
					// nothing to do
					// We don't have any previous snapshot so we can't report any metrics
					return;
				}

				Snapshot lastSnapshot = qr.prevSnapshots.get(qr.prevSnapshots.size()-1);
				for(MyTicket pT: lastSnapshot.tickets) {
					boolean found = false;
					for(MyTicket t: qr.currSnapshot.tickets)
						if( pT.getTicketId() == t.getTicketId()) {
							// if owner is same then nothing to do
							// otherwise the ticket got reassigned so update the counters accordingly
							if( !pT.getOwner().equals(t.getOwner()) ) {
								OwnerData od = qr.currSnapshot.ownerDataMap.get(t.getOwner());
								od.numReassignedTo++;

								OwnerData prevD = qr.currSnapshot.ownerDataMap.get(pT.getOwner());
								if( prevD == null) {
									prevD = new OwnerData(pT.getOwner());
									qr.currSnapshot.ownerDataMap.put(pT.getOwner(), prevD);
								}
								prevD.numReassignedFrom++;
							}
							found = true;
							break;
						}
					if( !found ) {
						// the ticket is no longer showing up in query
						// check it's current status
						String status = "closed";
						try {
							Ticket cT = tm.get(pT.getTicketId());
							status = cT.getStatus();
						}
						catch(RuntimeException e) {
							// ignore exception
						}
						OwnerData od = qr.currSnapshot.ownerDataMap.get(pT.getOwner());
						if( od == null ) {
							od = new OwnerData(pT.getOwner());
							qr.currSnapshot.ownerDataMap.put(pT.getOwner(), od);
						}
						if( status.equals("verifying") || status.equals("closed") )
							od.numClosed++;
						else
							od.numDeferred++;
					}
				}

				for(MyTicket t: qr.currSnapshot.tickets) {
					boolean found = false;
					for(MyTicket pT: lastSnapshot.tickets)
						if( pT.getTicketId() == t.getTicketId()) {
							found = true;
							break;
						}
					if( !found ) {
						// an incoming ticket
						// check the created date
						OwnerData od = qr.currSnapshot.ownerDataMap.get(t.getOwner());
						try {
							Ticket cT = tm.get(t.getTicketId());
							Date d = cT.getDateCreation();
							if( d.before(lastSnapshot.date) )
								od.numReopened++;
							else
								od.numNew++;
						}
						catch( RuntimeException e) {
							// assume that this is a new ticket
							od.numNew++;
						}
					}
				}
			}
		}
    }
    
    private void combineSnapshots() {
		for(QueryResults qr: resultsList) {
			if( qr != null ) {
				if( qr.prevSnapshots == null || qr.prevSnapshots.size() == 0 )
					continue;

				// add all the closed, deferred, new and reopened stats from all previous snapshots except first one
				Iterator<Entry<String, OwnerData>> it = qr.currSnapshot.ownerDataMap.entrySet().iterator();
				while( it.hasNext() ) {
					OwnerData od = it.next().getValue();
					Iterator<Snapshot> prevIt = qr.prevSnapshots.iterator();
					// ignore the first one in the list because that was the snapshot written
					if( prevIt.hasNext()) {
						prevIt.next();
						while( prevIt.hasNext() ) {
							OwnerData pod = prevIt.next().ownerDataMap.get(od.name);
							if( pod != null ) {
								od.numClosed += pod.numClosed;
								od.numDeferred += pod.numDeferred;
								od.numReassignedFrom += pod.numReassignedFrom;
								od.numNew += pod.numNew;
								od.numReopened += pod.numReopened;
								od.numReassignedTo += pod.numReassignedTo;
							}
						}
					}
				}

				// now find out if any owners appeared in previous snapshots but disappeared from the currSnapshot
				// this can happen if someone closes all defects.
				// ignore the first one in the list because that was the snapshot written
				for(int i=1; i<qr.prevSnapshots.size(); i++) {
					Iterator<Entry<String, OwnerData>> oit = qr.prevSnapshots.get(i).ownerDataMap.entrySet().iterator();
					while( oit.hasNext() ) {
						OwnerData pod = oit.next().getValue();
						OwnerData od = qr.currSnapshot.ownerDataMap.get(pod.name);
						if( od == null ) {
							od = new OwnerData(pod.name);
							qr.currSnapshot.ownerDataMap.put(pod.name,od);
							od.numClosed += pod.numClosed;
							od.numDeferred += pod.numDeferred;
							od.numReassignedFrom += pod.numReassignedFrom;
							od.numNew += pod.numNew;
							od.numReopened += pod.numReopened;
							od.numReassignedTo += pod.numReassignedTo;
							for(int j=i+1; j<qr.prevSnapshots.size(); j++) {
								OwnerData anod = qr.prevSnapshots.get(j).ownerDataMap.get(pod.name);
								if( anod != null ) {
									od.numClosed += anod.numClosed;
									od.numDeferred += anod.numDeferred;
									od.numReassignedFrom += anod.numReassignedFrom;
									od.numNew += anod.numNew;
									od.numReopened += anod.numReopened;
									od.numReassignedTo += anod.numReassignedTo; 
								}
							}
						}
					}
				}
			}
		}
    }
    
    private void writeOwnerData(java.sql.Timestamp currT) {
    	boolean flag = false;
		for(QueryResults qr: resultsList) {
			if( qr != null && qr.currSnapshot != null ) {
				flag = true;
				break;
			}
		}
		if( !flag ) {
			// we somehow got here but don't have any data to write, skip
			return;
		}

    	// write to a file
    	/*
    	String fileName = "cedarbugs" + String.valueOf(currSnapshot.date.getYear()+1900) + String.valueOf(currSnapshot.date.getMonth()+1) 
    			+ String.valueOf(currSnapshot.date.getDate()) + String.valueOf(currSnapshot.date.getHours()) + ".csv";
    	Writer writer = null;
    	try {
    	    writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fileName), "utf-8"));
    		writer.write("Name, Open, Closed, Deferred, ReassignedFrom, New, Reopened, ReassignedTo\n");
        	Iterator<Entry<String, OwnerData>> it = currSnapshot.ownerDataMap.entrySet().iterator();
        	while( it.hasNext() ) {
        		OwnerData od = it.next().getValue();
        		String str = od.name + ", " + od.numOpen + ", " + od.numClosed + ", " + od.numDeferred +
        				", " + od.numReassignedFrom + ", " + od.numNew + ", " + od.numReopened + ", " + od.numReassignedTo + "\n";
        		writer.write(str);
        	}
    	} catch (IOException ex) {
    	  // report
    	} finally {
    	   try {writer.close();} catch (Exception ex) {}
    	}
    	*/
    	
    	// write to gemfire XD
    	try {
    		// 1527 is the default port that a GemFire XD server uses to listen for thin client connections
    		java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:gemfirexd://localhost:1527/");

    		int key = 0;
    		java.sql.PreparedStatement ps = conn.prepareStatement("INSERT INTO SNAPSHOTS VALUES (DEFAULT, ?)", java.sql.Statement.RETURN_GENERATED_KEYS);
    		ps.setTimestamp(1, currT);
    		ps.executeUpdate();
    		java.sql.ResultSet rs = ps.getGeneratedKeys();
    		if (rs != null && rs.next()) {
    			key = rs.getInt(1);
    			System.out.println("Inserted row " + key);
    		}

    		if( key != 0 ) {
    			for(int qid = 0; qid<resultsList.size(); qid++) {
    				QueryResults qr = resultsList.get(qid);
    				if( qr != null && qr.currSnapshot != null ) {
    					// now insert all the ticket and owner data for this snapshot
    					java.sql.PreparedStatement pst = conn.prepareStatement("INSERT INTO TICKETS VALUES (?, ?, ?, ?, ?)");
    					for( MyTicket t: qr.currSnapshot.tickets) {
    						pst.clearParameters();
    						pst.setInt(1, key);
    						pst.setInt(2, qid);
    						pst.setInt(3, t.getTicketId());
    						pst.setString(4, t.getOwner());
    						pst.setString(5, t.getStatus());
    						pst.executeUpdate();
    					}
    					java.sql.PreparedStatement psod = conn.prepareStatement("INSERT INTO OWNERDATA VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
    					Iterator<Entry<String, OwnerData>> it = qr.currSnapshot.ownerDataMap.entrySet().iterator();
    					while( it.hasNext() ) {
    						OwnerData od = it.next().getValue();
    						psod.clearParameters();
    						psod.setInt(1, key);
    						psod.setInt(2, qid);
    						psod.setString(3, od.name);
    						psod.setInt(4, od.numOpen);
    						psod.setInt(5, od.numClosed);
    						psod.setInt(6, od.numDeferred);
    						psod.setInt(7, od.numReassignedFrom);
    						psod.setInt(8, od.numNew);
    						psod.setInt(9, od.numReopened);
    						psod.setInt(10, od.numReassignedTo);
    						psod.executeUpdate();
    					}
    				}
    			}
    		}
    		conn.commit();
    	}
    	catch (java.sql.SQLException ex) {
    		// handle any errors
    		System.out.println("SQLException: " + ex.getMessage());
    		System.out.println("SQLState: " + ex.getSQLState());
    		System.out.println("VendorError: " + ex.getErrorCode());
    	}

    }
}
