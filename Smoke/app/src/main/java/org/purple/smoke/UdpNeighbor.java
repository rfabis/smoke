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

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class UdpNeighbor extends Neighbor
{
    private DatagramSocket m_socket = null;
    private InetAddress m_inetAddress = null;

    protected String getLocalIp()
    {
	try
	{
	    if(m_socket != null && m_socket.getLocalAddress() != null)
		return m_socket.getLocalAddress().getHostAddress();
	}
	catch(Exception exception)
	{
	}

	if(m_version.equals("IPv4"))
	    return "0.0.0.0";
	else
	    return "::";
    }

    protected boolean connected()
    {
	try
	{
	    return m_socket != null && !m_socket.isClosed();
	}
	catch(Exception exception)
	{
	    return false;
	}
    }

    protected boolean send(String message)
    {
	if(!connected())
	    return false;

	try
	{
	    if(m_socket == null)
		return false;

	    DatagramPacket datagramPacket = new DatagramPacket
		(message.getBytes(),
		 message.getBytes().length,
		 m_inetAddress,
		 Integer.parseInt(m_ipPort));

	    m_socket.send(datagramPacket);
	    m_bytesWritten.getAndAdd(message.length());
	}
	catch(Exception exception)
	{
	    disconnect();
	    return false;
	}

	return false;
    }

    protected int getLocalPort()
    {
	try
	{
	    if(m_socket != null && !m_socket.isClosed())
		return m_socket.getLocalPort();
	}
	catch(Exception exception)
	{
	}

	return 0;
    }

    protected void disconnect()
    {
	try
	{
	    if(m_socket != null)
		m_socket.close();
	}
	catch(Exception exception)
	{
	}
	finally
	{
	    m_bytesRead.set(0);
	    m_bytesWritten.set(0);
	    m_socket = null;
	    m_startTime.set(System.nanoTime());
	}
    }

    protected void sendCapabilities()
    {
	if(!connected())
	    return;

	try
	{
	    String capabilities = "";

	    if(m_inetAddress == null || m_socket == null)
		return;

	    capabilities = getCapabilities();

	    DatagramPacket datagramPacket = new DatagramPacket
		(capabilities.getBytes(),
		 capabilities.getBytes().length,
		 m_inetAddress,
		 Integer.parseInt(m_ipPort));

	    m_socket.send(datagramPacket);
	    m_bytesWritten.getAndAdd(capabilities.length());
	}
	catch(Exception exception)
	{
	    disconnect();
	}
    }

    public UdpNeighbor(String ipAddress,
		       String ipPort,
		       String scopeId,
		       String version,
		       int oid)
    {
	super(ipAddress, ipPort, scopeId, "UDP", version, oid);

	try
	{
	    m_inetAddress = InetAddress.getByName(m_ipAddress);
	}
	catch(Exception exception)
	{
	    m_ipAddress = null;
	}

	m_readSocketScheduler = Executors.newSingleThreadScheduledExecutor();
	m_readSocketScheduler.scheduleAtFixedRate
	    (new Runnable()
	    {
		@Override
		public void run()
		{
		    if(!connected())
			return;

		    try
		    {
			ByteArrayOutputStream byteArrayOutputStream = null;
			int bytesRead = 0;

			if(m_socket == null)
			    return;

			DatagramPacket datagramPacket = null;

			byteArrayOutputStream = new ByteArrayOutputStream();
			datagramPacket = new DatagramPacket
			    (m_bytes, m_bytes.length);
			m_socket.receive(datagramPacket);

			if(datagramPacket.getLength() > 0)
			    byteArrayOutputStream.write
				(datagramPacket.getData(),
				 0,
				 datagramPacket.getLength());

			bytesRead += datagramPacket.getLength();

			if(bytesRead < 0)
			{
			    disconnect();
			    return;
			}

			m_bytesRead.getAndAdd(bytesRead);
			m_lastTimeRead.set(System.nanoTime());

			if(byteArrayOutputStream != null &&
			   byteArrayOutputStream.size() > 0)
			    synchronized(m_stringBuffer)
			    {
				m_stringBuffer.append
				    (new String(byteArrayOutputStream.
						toByteArray()));

				/*
				** Detect our end-of-message delimiter.
				*/

				int indexOf = m_stringBuffer.indexOf(s_eom);

				while(indexOf >= 0)
				{
				    String buffer = m_stringBuffer.
					substring(0, indexOf + s_eom.length());

				    if(!Kernel.ourMessage(buffer))
					echo(buffer);

				    m_stringBuffer.delete(0, buffer.length());
				    indexOf = m_stringBuffer.indexOf(s_eom);
				}

				if(m_stringBuffer.length() > s_maximumBytes)
				    m_stringBuffer.setLength(s_maximumBytes);
			    }
		    }
		    catch(Exception exception)
		    {
		    }
		}
	    }, 0, s_readSocketInterval, TimeUnit.MILLISECONDS);
    }

    public void abort()
    {
	disconnect();
	super.abort();
	m_readSocketScheduler.shutdown();

	try
	{
	    m_readSocketScheduler.awaitTermination(60, TimeUnit.SECONDS);
	}
	catch(Exception exception)
	{
	}
    }

    public void connect()
    {
	if(connected())
	    return;

	try
	{
	    if(m_inetAddress == null)
		return;

	    m_bytesRead.set(0);
	    m_bytesWritten.set(0);
	    m_lastTimeRead.set(System.nanoTime());
	    m_socket = new DatagramSocket();
	    m_socket.connect(m_inetAddress, Integer.parseInt(m_ipPort));
	    m_socket.setSoTimeout(s_soTimeout);
	    m_startTime.set(System.nanoTime());
	}
	catch(Exception exception)
	{
	    disconnect();
	}
    }
}
