/*
 * JS-Collider framework.
 * Copyright (C) 2013 Sergey Zubarev
 * info@js-labs.org
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jsl.collider;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;


public class Collider
{
    public static class Config
    {
        public int threadPoolThreads;
        public boolean useDirectBuffers;
        public int shutdownTimeout;

        public int socketSendBufSize;
        public int socketRecvBufSize;
        public int inputQueueBlockSize;
        public int outputQueueBlockSize;

        public Config()
        {
            threadPoolThreads = 0;
            useDirectBuffers  = true;
            shutdownTimeout   = 60;

            /* Use system default settings by default */
            socketSendBufSize = 0;
            socketRecvBufSize = 0;

            inputQueueBlockSize   = (32 * 1024);
            outputQueueBlockSize  = (16 * 1024);
        }
    }

    public interface ChannelHandler
    {
        public void handleReadyOps( Executor executor );
    }

    public static abstract class SelectorThreadRunnable
    {
        public volatile SelectorThreadRunnable nextSelectorThreadRunnable;
        abstract public void runInSelectorThread();
    }

    private class Stopper extends SelectorThreadRunnable implements Runnable
    {
        public void runInSelectorThread()
        {
            Set<SelectionKey> keys = m_selector.keys();
            for (SelectionKey key : keys)
            {
                Object attachment = key.attachment();
                if (attachment instanceof SessionImpl)
                    ((SessionImpl)attachment).closeConnection();
                /*
                 * else if (attachement instanceof AcceptorImpl)
                 * {
                 *     Can happen, canceled SelectionKey is not removed from the
                 *     Selector ritgh at the SelectionKey.cancel() call,
                 *     but will present in the keys set till the next
                 *     Selector.select() call.
                 * }
                 */
            }
            m_state = ST_STOPPING;
        }

        public void run()
        {
            SessionEmitter [] emitters = null;
            synchronized (m_emitters)
            {
                int size = m_emitters.size();
                if (size > 0)
                {
                    emitters = new SessionEmitter[size];
                    Iterator<SessionEmitter> it = m_emitters.keySet().iterator();
                    for (int idx=0; idx<size; idx++)
                        emitters[idx] = it.next();
                }
            }
            if (emitters != null)
            {
                for (SessionEmitter emitter : emitters)
                    removeEmitter( emitter );
            }
            executeInSelectorThread( this );
        }
    }

    private void removeEmitter( SessionEmitter emitter )
    {
        SessionEmitterImpl emitterImpl;
        synchronized (m_emitters)
        {
            if (!m_emitters.containsKey(emitter))
                return;
            emitterImpl = m_emitters.get( emitter );
            m_emitters.remove( emitter );
        }
        assert( emitterImpl != null );
        emitterImpl.stop();
    }

    private final static int ST_RUNNING = 1;
    private final static int ST_STOPPING = 2;

    private static final Logger s_logger = Logger.getLogger( Collider.class.getName() );

    private Config m_config;
    private Selector m_selector;
    private ExecutorService m_executor;
    private int m_state;
    private boolean m_stop;
    private final Map<SessionEmitter, SessionEmitterImpl> m_emitters;
    private volatile SelectorThreadRunnable m_strHead;
    private AtomicReference<SelectorThreadRunnable> m_strTail;

    public Collider() throws IOException
    {
        this( new Config() );
    }

    public Collider( Config config ) throws IOException
    {
        m_config = config;
        m_selector = Selector.open();

        int threadPoolThreads = config.threadPoolThreads;
        if (threadPoolThreads == 0)
            threadPoolThreads = Runtime.getRuntime().availableProcessors();
        if (threadPoolThreads < 4)
            threadPoolThreads = 4;
        m_executor = Executors.newFixedThreadPool( threadPoolThreads );

        m_state = ST_RUNNING;
        m_stop = false;
        m_emitters = Collections.synchronizedMap( new HashMap<SessionEmitter, SessionEmitterImpl>() );
        m_strHead = null;
        m_strTail = new AtomicReference<SelectorThreadRunnable>();
    }

    public Config getConfig()
    {
        return m_config;
    }

    public void run() throws IOException
    {
        if (s_logger.isLoggable(Level.FINE))
            s_logger.fine( "Collider started." );

        for (;;)
        {
            m_selector.select();

            Set<SelectionKey> selectedKeys = m_selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
            while (keyIterator.hasNext())
            {
                SelectionKey key = keyIterator.next();
                ChannelHandler channelHandler = (ChannelHandler) key.attachment();
                channelHandler.handleReadyOps( m_executor );
                keyIterator.remove();
            }

            while (m_strHead != null)
            {
                m_strHead.runInSelectorThread();
                SelectorThreadRunnable head = m_strHead;
                SelectorThreadRunnable next = m_strHead.nextSelectorThreadRunnable;
                if (next == null)
                {
                    m_strHead = null;
                    if (m_strTail.compareAndSet(head, null))
                        break;
                    while (head.nextSelectorThreadRunnable == null);
                    next = head.nextSelectorThreadRunnable;
                }
                m_strHead = next;
                head.nextSelectorThreadRunnable = null;
            }

            if (m_state == ST_STOPPING)
            {
                if (m_selector.keys().size() == 0)
                    break;
                m_selector.wakeup();
            }
        }

        m_executor.shutdown();
        try
        {
            if (!m_executor.awaitTermination(m_config.shutdownTimeout, TimeUnit.SECONDS))
                m_executor.shutdownNow();
            m_executor.awaitTermination( m_config.shutdownTimeout, TimeUnit.SECONDS );
        }
        catch (InterruptedException ignored) {}

        if (s_logger.isLoggable(Level.FINE))
            s_logger.fine( "Collider stopped." );
    }


    public void stop()
    {
        if (s_logger.isLoggable(Level.FINE))
            s_logger.fine( "Collider.stop() called." );

        synchronized (m_emitters)
        {
            if (m_stop)
                return;
            m_stop = true;
        }
        executeInThreadPool( new Stopper() );
    }


    public void executeInSelectorThread( SelectorThreadRunnable runnable )
    {
        assert( runnable.nextSelectorThreadRunnable == null );
        SelectorThreadRunnable tail = m_strTail.getAndSet( runnable );
        if (tail == null)
        {
            m_strHead = runnable;
            m_selector.wakeup();
        }
        else
            tail.nextSelectorThreadRunnable = runnable;
    }


    public void executeInThreadPool( Runnable runnable )
    {
        m_executor.execute( runnable );
    }


    public void addAcceptor( Acceptor acceptor )
    {
        boolean stop;
        boolean alreadyRegistered = false;

        synchronized (m_emitters)
        {
            stop = m_stop;
            if (!stop)
            {
                if (m_emitters.containsKey(acceptor))
                    alreadyRegistered = true;
                else
                    m_emitters.put( acceptor, null );
            }
        }

        if (stop)
        {
            acceptor.onAcceptorStartingFailure( "Collider is not running." );
            return;
        }
        else if (alreadyRegistered)
        {
            acceptor.onAcceptorStartingFailure( "Acceptor already registered" );
            return;
        }

        try
        {
            ServerSocketChannel channel = ServerSocketChannel.open();
            channel.configureBlocking( false );
            channel.setOption( StandardSocketOptions.SO_REUSEADDR, acceptor.reuseAddr );
            channel.socket().bind( acceptor.getAddr() );

            AcceptorImpl acceptorImpl = new AcceptorImpl( this, m_selector, channel, acceptor );
            synchronized (m_emitters) { m_emitters.put( acceptor, acceptorImpl ); }
            acceptorImpl.start();
        }
        catch (IOException e)
        {
            synchronized (m_emitters) { m_emitters.remove( acceptor ); }
            acceptor.onAcceptorStartingFailure( e.getMessage() );
        }
    }


    public void removeAcceptor( Acceptor acceptor )
    {
        removeEmitter( acceptor );
    }
}
