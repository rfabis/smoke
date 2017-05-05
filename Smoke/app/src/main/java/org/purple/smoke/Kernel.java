/*
** Copyright (c) Alexis Megas.
** All rights reserved.
**
** Redistribution and use in source and binary forms, with or without
** modification, are permitted provided that the following conditions
** are met:
** 1. Redistributions of source code must retain the above copyright
**    notice, this list of conditions and the following disclaimer.
** 2. Redistributions in binary form must reproduce the above copyright
**    notice, this list of conditions and the following disclaimer in the
**    documentation and/or other materials provided with the distribution.
** 3. The name of the author may not be used to endorse or promote products
**    derived from Smoke without specific prior written permission.
**
** SMOKE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
** IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
** OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
** IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
** INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
** NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
** DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
** THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
** (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
** SMOKE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package org.purple.smoke;

import android.content.Intent;
import android.util.Base64;
import android.util.SparseArray;
import android.util.SparseIntArray;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Kernel
{
    private class ParticipantCall
    {
	public byte m_keyStream[] = null;
	public int m_participantOid = -1;
	public long m_startTime = -1; // Calls expire.

	public ParticipantCall(int participantOid)
	{
	    m_participantOid = participantOid;
	    m_startTime = System.nanoTime();
	}

	public void prepareHalfKeys()
	{
	    m_keyStream = Cryptography.randomBytes(48); /*
							** 0.5 (AES) +
							** 0.5 (SHA-512)
							*/
	}
    }

    private Hashtable<String, ParticipantCall> m_callQueue = null;
    private ScheduledExecutorService m_callScheduler = null;
    private ScheduledExecutorService m_congestionScheduler = null;
    private ScheduledExecutorService m_neighborsScheduler = null;
    private final SparseArray<Neighbor> m_neighbors = new SparseArray<> ();
    private final static Database s_databaseHelper = Database.getInstance();
    private final static Cryptography s_cryptography =
	Cryptography.getInstance();
    private final static Object s_callQueueMutex = new Object();
    private final static SipHash s_congestionSipHash = new SipHash
	(Cryptography.randomBytes(SipHash.KEY_LENGTH));
    private final static int CALL_INTERVAL = 250; // 0.250 Seconds
    private final static int CALL_LIFETIME = 30000;
    private final static int CONGESTION_INTERVAL = 15000; // 15 Seconds
    private final static int CONGESTION_LIFETIME = 30;
    private final static int NEIGHBORS_INTERVAL = 5000; // 5 Seconds
    private static Kernel s_instance = null;

    private Kernel()
    {
	m_callQueue = new Hashtable<String, ParticipantCall> ();
	prepareSchedulers();
    }

    private void prepareNeighbors()
    {
	ArrayList<NeighborElement> neighbors =
	    s_databaseHelper.readNeighbors(s_cryptography);

	if(neighbors == null || neighbors.size() == 0)
	{
	    purge();
	    return;
	}
	else
	    synchronized(m_neighbors)
	    {
		for(int i = m_neighbors.size() - 1; i >= 0; i--)
		{
		    /*
		    ** Remove neighbor objects which do not exist in the
		    ** database.
		    */

		    boolean found = false;
		    int oid = m_neighbors.keyAt(i);

		    for(int j = 0; j < neighbors.size(); j++)
			if(neighbors.get(j) != null &&
			   neighbors.get(j).m_oid == oid)
			{
			    found = true;
			    break;
			}

		    if(!found)
		    {
			if(m_neighbors.get(oid) != null)
			    m_neighbors.get(oid).abort();

			m_neighbors.remove(oid);
		    }
		}
	    }

	for(int i = 0; i < neighbors.size(); i++)
	{
	    NeighborElement neighborElement = neighbors.get(i);

	    if(neighborElement == null)
		continue;
	    else
	    {
		synchronized(m_neighbors)
		{
		    if(m_neighbors.get(neighborElement.m_oid) != null)
			continue;
		}

		if(neighborElement.m_statusControl.toLowerCase().
		   equals("delete") ||
		   neighborElement.m_statusControl.toLowerCase().
		   equals("disconnect"))
		{
		    if(neighborElement.m_statusControl.toLowerCase().
		       equals("disconnect"))
			s_databaseHelper.saveNeighborInformation
			    (s_cryptography,
			     "0",
			     "0",
			     "0",
			     "",
			     "0",
			     "",
			     "",
			     "disconnected",
			     "0",
			     String.valueOf(neighborElement.m_oid));

		    continue;
		}
	    }

	    Neighbor neighbor = null;

	    if(neighborElement.m_transport.equals("TCP"))
		neighbor = new TcpNeighbor
		    (neighborElement.m_remoteIpAddress,
		     neighborElement.m_remotePort,
		     neighborElement.m_remoteScopeId,
		     neighborElement.m_ipVersion,
		     neighborElement.m_oid);
	    else if(neighborElement.m_transport.equals("UDP"))
	    {
		try
		{
		    InetAddress inetAddress = InetAddress.getByName
			(neighborElement.m_remoteIpAddress);

		    if(inetAddress.isMulticastAddress())
			neighbor = new UdpMulticastNeighbor
			    (neighborElement.m_remoteIpAddress,
			     neighborElement.m_remotePort,
			     neighborElement.m_remoteScopeId,
			     neighborElement.m_ipVersion,
			     neighborElement.m_oid);
		    else
			neighbor = new UdpNeighbor
			    (neighborElement.m_remoteIpAddress,
			     neighborElement.m_remotePort,
			     neighborElement.m_remoteScopeId,
			     neighborElement.m_ipVersion,
			     neighborElement.m_oid);
		}
		catch(Exception exception)
		{
		}
	    }

	    if(neighbor == null)
		continue;

	    synchronized(m_neighbors)
	    {
		m_neighbors.append(neighborElement.m_oid, neighbor);
	    }
	}

	Runtime.getRuntime().runFinalization();
    }

    private void prepareSchedulers()
    {
	if(m_callScheduler == null)
	{
	    m_callScheduler = Executors.newSingleThreadScheduledExecutor();
	    m_callScheduler.scheduleAtFixedRate(new Runnable()
	    {
		@Override
		public void run()
		{
		    try
		    {
			String sipHashId = "";
			boolean notify = false;
			int participantOid = -1;
			long startTime = -1;

			synchronized(s_callQueueMutex)
			{
			    if(m_callQueue.isEmpty())
				return;

			    /*
			    ** Remove expired calls.
			    */

			    Iterator<Hashtable.Entry<String, ParticipantCall> >
				it = m_callQueue.entrySet().iterator();

			    while(it.hasNext())
			    {
				Hashtable.Entry<String, ParticipantCall> entry =
				    it.next();

				if(it == null)
				{
				    it.remove();
				    return;
				}

				if((System.nanoTime() - entry.getValue().
				    m_startTime) / 1000000 > CALL_LIFETIME)
				{
				    it.remove();
				    notify = true;
				}
			    }

			    /*
			    ** Discover a pending call.
			    */

			    for(String string : m_callQueue.keySet())
			    {
				if(m_callQueue.get(string).m_keyStream != null)
				    continue;

				participantOid = m_callQueue.get(string).
				    m_participantOid;
				sipHashId = string;
				startTime = m_callQueue.get(string).
				    m_startTime;
				break;
			    }

			    if(notify)
			    {
				/*
				** Expired call(s). Notify some activity.
				*/

				Intent intent = new Intent
				    ("org.purple.smoke.populate_participants");

				Smoke.getApplication().sendBroadcast(intent);
				return;
			    }

			    if(participantOid == -1)
				/*
				** A new call does not exist.
				*/

				return;

			    ParticipantCall participantCall = m_callQueue.get
				(sipHashId);

			    participantCall.prepareHalfKeys();
			    m_callQueue.put(sipHashId, participantCall);
			}

			/*
			** Notify some activity to refresh itself.
			*/

			Intent intent = new Intent
			    ("org.purple.smoke.populate_participants");

			Smoke.getApplication().sendBroadcast(intent);

			/*
			** Place a call request to all neighbors.
			*/
		    }
		    catch(Exception exception)
		    {
		    }
		}
	    }, 1500, CALL_INTERVAL, TimeUnit.MILLISECONDS);
	}

	if(m_congestionScheduler == null)
	{
	    m_congestionScheduler = Executors.
		newSingleThreadScheduledExecutor();
	    m_congestionScheduler.scheduleAtFixedRate(new Runnable()
	    {
		@Override
		public void run()
		{
		    s_databaseHelper.purgeCongestion(CONGESTION_LIFETIME);
		}
	    }, 1500, CONGESTION_INTERVAL, TimeUnit.MILLISECONDS);
	}

	if(m_neighborsScheduler == null)
	{
	    m_neighborsScheduler = Executors.newSingleThreadScheduledExecutor();
	    m_neighborsScheduler.scheduleAtFixedRate(new Runnable()
	    {
		@Override
		public void run()
		{
		    prepareNeighbors();
		}
	    }, 1500, NEIGHBORS_INTERVAL, TimeUnit.MILLISECONDS);
	}
    }

    private void purge()
    {
	/*
	** Disconnect all existing sockets.
	*/

	synchronized(m_neighbors)
	{
	    for(int i = 0; i < m_neighbors.size(); i++)
	    {
		int j = m_neighbors.keyAt(i);

		if(m_neighbors.get(j) != null)
		    m_neighbors.get(j).abort();
	    }

	    m_neighbors.clear();
	}

	Runtime.getRuntime().runFinalization();
    }

    public int callingStreamLength(String sipHashId)
    {
	synchronized(s_callQueueMutex)
	{
	    if(m_callQueue.containsKey(sipHashId) &&
	       m_callQueue.get(sipHashId) != null)
		return m_callQueue.get(sipHashId).m_keyStream == null ? -1 :
		    m_callQueue.get(sipHashId).m_keyStream.length;
	}

	return -1;
    }

    public static boolean ourMessage(String buffer)
    {
	if(s_databaseHelper.containsCongestionDigest(s_congestionSipHash.
						     hmac(buffer.getBytes())))
	    return true;

	ByteArrayInputStream stream = null;
	ObjectInputStream input = null;

	try
	{
	    stream = new ByteArrayInputStream
		(Base64.decode(Messages.stripMessage(buffer), Base64.DEFAULT));
	    input = new ObjectInputStream(stream);

	    byte array1[] = (byte []) input.readObject();
	    byte array2[] = (byte []) input.readObject();
	    byte array3[] = (byte []) input.readObject();

	    if(array1 == null || array2 == null || array3 == null)
		return false;

	    /*
	    ** EPKS?
	    */

	    if(s_cryptography.isValidSipHashMac(array1, array2))
	    {
		stream = new ByteArrayInputStream
		    (s_cryptography.decryptWithSipHashKey(array1));
		input = new ObjectInputStream(stream);

		if(s_databaseHelper.writeParticipant(s_cryptography, input))
		{
		    Intent intent = new Intent
			("org.purple.smoke.populate_participants");

		    Smoke.getApplication().sendBroadcast(intent);
		}
	    }

	    s_databaseHelper.writeCongestionDigest
		(s_congestionSipHash.hmac(buffer.getBytes()));
	}
	catch(Exception exception)
	{
	    return false;
	}
	finally
	{
	    try
	    {
		if(input != null)
		    input.close();

		if(stream != null)
		    stream.close();
	    }
	    catch(Exception exception)
	    {
	    }
	}

	return true;
    }

    public static synchronized Kernel getInstance()
    {
	if(s_instance == null)
	    s_instance = new Kernel();

	return s_instance;
    }

    public static void writeCongestionDigest(String message)
    {
	s_databaseHelper.writeCongestionDigest
	    (s_congestionSipHash.hmac(message.getBytes()));
    }

    public static void writeCongestionDigest(byte data[])
    {
	s_databaseHelper.writeCongestionDigest
	    (s_congestionSipHash.hmac(data)); /*
					      ** Zero on hmac() failure.
					      ** Acceptable.
					      */
    }

    public void call(int participantOid, String sipHashId)
    {
	/*
	** Calling messages are not placed in the outbound_queue
	** as they are considered temporary.
	*/

	synchronized(s_callQueueMutex)
	{
	    if(m_callQueue.containsKey(sipHashId))
		m_callQueue.remove(sipHashId);

	    m_callQueue.put(sipHashId, new ParticipantCall(participantOid));
	}
    }

    public void clearNeighborQueues()
    {
	synchronized(m_neighbors)
	{
	    for(int i = 0; i < m_neighbors.size(); i++)
	    {
		int j = m_neighbors.keyAt(i);

		if(m_neighbors.get(j) != null)
		    m_neighbors.get(j).clearQueue();
	    }
	}
    }

    public void echo(String message, int oid)
    {
	if(!State.getInstance().neighborsEcho() || message.trim().isEmpty())
	    return;

	if(s_databaseHelper.
	   containsCongestionDigest(s_congestionSipHash.hmac(message.
							     getBytes())))
	    return;

	synchronized(m_neighbors)
	{
	    for(int i = 0; i < m_neighbors.size(); i++)
	    {
		int j = m_neighbors.keyAt(i);

		if(m_neighbors.get(j) != null &&
		   m_neighbors.get(j).getOid() != oid)
		    m_neighbors.get(j).scheduleSend(message);
	    }
	}
    }

    public void enqueueMessage(String message)
    {
	if(message.trim().isEmpty())
	    return;

	SparseIntArray neighbors = s_databaseHelper.readNeighborOids();

	if(neighbors != null)
	    for(int i = 0; i < neighbors.size(); i++)
		s_databaseHelper.enqueueOutboundMessage
		    (message, neighbors.get(i));
    }
}
