package com.nineteendrops.tracdrops.api;

import com.nineteendrops.tracdrops.client.Trac;
import com.nineteendrops.tracdrops.client.api.core.ApiVersion;
import com.nineteendrops.tracdrops.client.api.core.CoreManager;
import com.nineteendrops.tracdrops.client.api.wiki.WikiManager;
import com.nineteendrops.tracdrops.client.api.ticket.ticket.TicketAttachment;
import com.nineteendrops.tracdrops.client.api.ticket.ticket.TicketManager;
import com.nineteendrops.tracdrops.client.api.ticket.ticket.TicketQuery;
import com.nineteendrops.tracdrops.client.api.ticket.ticket.TicketQueryFilter;
import com.nineteendrops.tracdrops.client.api.ticket.ticket.Ticket;

import java.io.BufferedWriter;
import java.io.File;
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



/**
 * Created www.19drops.com
 * User: 19drops
 * Date: 23-ago-2009
 * Time: 13:12:55
 * <p/>
 * This material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 * <p/>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 * <p/>
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA 02110-1301 USA
 */
public class Test extends TimerTask {
	
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
	
	private class Snapshot {
		private ArrayList<Ticket> tickets = null;
		private java.sql.Timestamp date = null;
		private TreeMap<String,OwnerData> ownerDataMap = null;

		public Snapshot() {
			tickets = new ArrayList<Ticket>();
			date = new java.sql.Timestamp(System.currentTimeMillis());
			ownerDataMap = new TreeMap<String,OwnerData>();
		}
	}
	
	private final int SNAPSHOT_WRITE = 3;
	private ArrayList<Snapshot> prevSnapshots = null;
	private Snapshot currSnapshot = null;

    public static void main(String[] args) throws Exception{
        TimerTask timerTask = new Test();
        // running timer task as daemon thread
        Timer timer = new Timer();
        // schedule task to be run every 5 minutes
        timer.scheduleAtFixedRate(timerTask, 0, 5*60*1000);
    }
    
    public void run() {
        Trac trac = new Trac( "https://svn.gemstone.com/trac/gemfire/login/xmlrpc", "vjoshi", "p1v0tal" );
        trac.initialize();

        TicketManager ticketManager = trac.getTicketManager();
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
        
        currSnapshot = new Snapshot();
        ArrayList<Integer> results = ticketManager.query(q);
        
        for( Integer tid: results ) {
        	Ticket t = ticketManager.get(tid);
        	currSnapshot.tickets.add(t);
        	String name = t.getOwner();
        	OwnerData o = currSnapshot.ownerDataMap.get(name);
        	if( o == null )
        	{
        		o = new OwnerData(name);
        		currSnapshot.ownerDataMap.put(name,o);
        	}
        	o.numOpen++;
        }
        
        updateOwnerData(ticketManager);
        if( prevSnapshots == null || prevSnapshots.size() >= SNAPSHOT_WRITE ) {
        	combineSnapshots();
        	writeOwnerData();
        	// reset the prevSnapshots
        	prevSnapshots = new ArrayList<Snapshot>();
        }
    	prevSnapshots.add(currSnapshot);
    	currSnapshot = null;
    }
    
    private void updateOwnerData(TicketManager tm) {
        if( prevSnapshots == null || prevSnapshots.size() == 0 ) {
        	for(Ticket t: currSnapshot.tickets) {
    			// every ticket is an incoming new ticket as we don't have any previous snapshot
				OwnerData od = currSnapshot.ownerDataMap.get(t.getOwner());
    			od.numNew++;
    		}
        	return;
        }
        
        Snapshot lastSnapshot = prevSnapshots.get(prevSnapshots.size()-1);
    	for(Ticket pT: lastSnapshot.tickets) {
    		boolean found = false;
    		for(Ticket t: currSnapshot.tickets)
    			if( pT.getIdTicket() == t.getIdTicket()) {
    				// if owner is same then nothing to do
    				// otherwise the ticket got reassigned so update the counters accordingly
    				if( !pT.getOwner().equals(t.getOwner()) ) {
    					OwnerData od = currSnapshot.ownerDataMap.get(t.getOwner());
    					od.numReassignedTo++;
    					
    					OwnerData prevD = currSnapshot.ownerDataMap.get(pT.getOwner());
    					if( prevD == null) {
    		        		prevD = new OwnerData(pT.getOwner());
    		        		currSnapshot.ownerDataMap.put(pT.getOwner(), prevD);
    		        	}
    					prevD.numReassignedFrom++;
    				}
    				found = true;
    				break;
    			}
    		if( !found ) {
    			// the ticket is no longer showing up in query
    			// check it's current status
    			Ticket cT = tm.get(pT.getIdTicket());
    			String status = cT.getStatus();
				OwnerData od = currSnapshot.ownerDataMap.get(pT.getOwner());
    			if( status.equals("verifying") || status.equals("closed") )
    				od.numClosed++;
    			else
    				od.numDeferred++;
    		}
    	}

    	for(Ticket t: currSnapshot.tickets) {
    		boolean found = false;
    		for(Ticket pT: lastSnapshot.tickets)
    			if( pT.getIdTicket() == t.getIdTicket()) {
    				found = true;
    				break;
    			}
    		if( !found ) {
    			// an incoming ticket
    			// check the created date
				OwnerData od = currSnapshot.ownerDataMap.get(t.getOwner());
    			Date d = t.getDateCreation();
    			if( d.before(lastSnapshot.date) )
    				od.numReopened++;
    			else
    				od.numNew++;
    		}
    	}
    }
    
    private void combineSnapshots() {
    	if( prevSnapshots == null )
    		return;
    	
    	// add all the closed, deferred, new and reopened stats from all previous snapshots except first one
    	Iterator<Entry<String, OwnerData>> it = currSnapshot.ownerDataMap.entrySet().iterator();
    	while( it.hasNext() ) {
    		OwnerData od = it.next().getValue();
    		Iterator<Snapshot> prevIt = prevSnapshots.iterator();
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
		for(int i=1; i<prevSnapshots.size(); i++) {
			Iterator<Entry<String, OwnerData>> oit = prevSnapshots.get(i).ownerDataMap.entrySet().iterator();
			while( oit.hasNext() ) {
				OwnerData pod = oit.next().getValue();
				OwnerData od = currSnapshot.ownerDataMap.get(pod.name);
				if( od == null ) {
					od = new OwnerData(pod.name);
					currSnapshot.ownerDataMap.put(pod.name,od);
					od.numClosed += pod.numClosed;
					od.numDeferred += pod.numDeferred;
					od.numReassignedFrom += pod.numReassignedFrom;
					od.numNew += pod.numNew;
					od.numReopened += pod.numReopened;
   					od.numReassignedTo += pod.numReassignedTo;
   					for(int j=i+1; j<prevSnapshots.size(); j++) {
	    				OwnerData anod = prevSnapshots.get(j).ownerDataMap.get(pod.name);
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
    
    private void writeOwnerData() {
    	// write to a file
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
    	
    	// write to gemfire XD
    	try {
    		// java.util.Properties p = new java.util.Properties();

    		// 1527 is the default port that a GemFire XD server uses to listen for thin client connections
    		java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:gemfirexd://localhost:1527/");

    		java.sql.PreparedStatement ps = conn.prepareStatement("INSERT INTO SNAPSHOTS VALUES (DEFAULT, ?)", java.sql.Statement.RETURN_GENERATED_KEYS);
    		ps.setTimestamp(1, currSnapshot.date);
    		ps.executeUpdate();
    		java.sql.ResultSet rs = ps.getGeneratedKeys();
    		if (rs != null && rs.next()) {
    		    int key = rs.getInt(1);
    			System.out.println("Inserted row " + key);
    			
    			// now insert all the ticket and owner data for this snapshot
        		java.sql.PreparedStatement pst = conn.prepareStatement("INSERT INTO TICKETS VALUES (?, ?, ?, ?)");
        		for( Ticket t: currSnapshot.tickets) {
        			pst.clearParameters();
        			pst.setInt(1, key);
        			pst.setInt(2, t.getIdTicket());
        			pst.setString(3, t.getOwner());
        			pst.setString(4, t.getStatus());
        			pst.executeUpdate();
        		}
        		java.sql.PreparedStatement psod = conn.prepareStatement("INSERT INTO OWNERDATA VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
            	Iterator<Entry<String, OwnerData>> it = currSnapshot.ownerDataMap.entrySet().iterator();
            	while( it.hasNext() ) {
            		OwnerData od = it.next().getValue();
            		psod.clearParameters();
            		psod.setInt(1, key);
            		psod.setString(2, od.name);
            		psod.setInt(3, od.numOpen);
            		psod.setInt(4, od.numClosed);
            		psod.setInt(5, od.numDeferred);
            		psod.setInt(6, od.numReassignedFrom);
            		psod.setInt(7, od.numNew);
            		psod.setInt(8, od.numReopened);
            		psod.setInt(9, od.numReassignedTo);
             		psod.executeUpdate();
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
