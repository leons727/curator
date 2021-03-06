/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.curator.framework.recipes.shared;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.framework.listen.ListenerContainer;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.utils.PathUtils;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages a shared value. All clients watching the same path will have the up-to-date
 * value (considering ZK's normal consistency guarantees).
 */
public class SharedValue implements Closeable, SharedValueReader
{
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ListenerContainer<SharedValueListener> listeners = new ListenerContainer<SharedValueListener>();
    private final CuratorFramework client;
    private final String path;
    private final byte[] seedValue;
    private final AtomicReference<State> state = new AtomicReference<State>(State.LATENT);
    private final AtomicReference<VersionedValue<byte[]>> currentValue;

    private final CuratorWatcher watcher = new CuratorWatcher()
    {
        @Override
        public void process(WatchedEvent event) throws Exception
        {
            if ( state.get() == State.STARTED )
            {
                readValue();
                notifyListeners();
            }
        }
    };

    private final ConnectionStateListener connectionStateListener = new ConnectionStateListener()
    {
        @Override
        public void stateChanged(CuratorFramework client, ConnectionState newState)
        {
            notifyListenerOfStateChanged(newState);
        }
    };

    private enum State
    {
        LATENT,
        STARTED,
        CLOSED
    }

    /**
     * @param client    the client
     * @param path      the shared path - i.e. where the shared value is stored
     * @param seedValue the initial value for the value if/f the path has not yet been created
     */
    public SharedValue(CuratorFramework client, String path, byte[] seedValue)
    {
        this.client = client;
        this.path = PathUtils.validatePath(path);
        this.seedValue = Arrays.copyOf(seedValue, seedValue.length);
        currentValue = new AtomicReference<VersionedValue<byte[]>>(new VersionedValue<byte[]>(0, Arrays.copyOf(seedValue, seedValue.length)));
    }

    @Override
    public byte[] getValue()
    {
        VersionedValue<byte[]> localCopy = currentValue.get();
        return Arrays.copyOf(localCopy.getValue(), localCopy.getValue().length);
    }

    @Override
    public VersionedValue<byte[]> getVersionedValue()
    {
        VersionedValue<byte[]> localCopy = currentValue.get();
        return new VersionedValue<byte[]>(localCopy.getVersion(), Arrays.copyOf(localCopy.getValue(), localCopy.getValue().length));
    }

    /**
     * Change the shared value value irrespective of its previous state
     *
     * @param newValue new value
     * @throws Exception ZK errors, interruptions, etc.
     */
    public void setValue(byte[] newValue) throws Exception
    {
        Preconditions.checkState(state.get() == State.STARTED, "not started");

        VersionedValue<byte[]> localCopy = currentValue.get();
        client.setData().forPath(path, newValue);

        currentValue.set(new VersionedValue<byte[]>(localCopy.getVersion() + 1, Arrays.copyOf(newValue, newValue.length)));
    }

    /**
     * Changes the shared value only if its value has not changed since this client last
     * read it. If the value has changed, the value is not set and this client's view of the
     * value is updated. i.e. if the value is not successful you can get the updated value
     * by calling {@link #getValue()}.
     *
     * @param newValue the new value to attempt
     * @return true if the change attempt was successful, false if not. If the change
     * was not successful, {@link #getValue()} will return the updated value
     * @throws Exception ZK errors, interruptions, etc.
     */
    public boolean trySetValue(byte[] newValue) throws Exception
    {
        Preconditions.checkState(state.get() == State.STARTED, "not started");

        try
        {
            VersionedValue<byte[]> localCopy = currentValue.get();
            client.setData().withVersion(localCopy.getVersion()).forPath(path, newValue);
            currentValue.set(new VersionedValue<byte[]>(localCopy.getVersion() + 1, Arrays.copyOf(newValue, newValue.length)));
            return true;
        }
        catch ( KeeperException.BadVersionException ignore )
        {
            // ignore
        }

        readValue();
        return false;
    }

    /**
     * Changes the shared value only if its value has not changed since the version specified by
     * newValue. If the value has changed, the value is not set and this client's view of the
     * value is updated. i.e. if the value is not successful you can get the updated value
     * by calling {@link #getValue()}.
     *
     * @param newValue the new value to attempt
     * @return true if the change attempt was successful, false if not. If the change
     * was not successful, {@link #getValue()} will return the updated value
     * @throws Exception ZK errors, interruptions, etc.
     */
    public boolean trySetValue(VersionedValue<byte[]> newValue) throws Exception
    {
        Preconditions.checkState(state.get() == State.STARTED, "not started");

        try
        {
            client.setData().withVersion(newValue.getVersion()).forPath(path, newValue.getValue());
            currentValue.set(new VersionedValue<byte[]>(newValue.getVersion() + 1, Arrays.copyOf(newValue.getValue(), newValue.getValue().length)));
            return true;
        }
        catch ( KeeperException.BadVersionException ignore )
        {
            // ignore
        }

        readValue();
        return false;
    }

    /**
     * Returns the listenable
     *
     * @return listenable
     */
    public ListenerContainer<SharedValueListener> getListenable()
    {
        return listeners;
    }

    /**
     * The shared value must be started before it can be used. Call {@link #close()} when you are
     * finished with the shared value
     *
     * @throws Exception ZK errors, interruptions, etc.
     */
    public void start() throws Exception
    {
        Preconditions.checkState(state.compareAndSet(State.LATENT, State.STARTED), "Cannot be started more than once");

        client.getConnectionStateListenable().addListener(connectionStateListener);
        try
        {
            client.create().creatingParentsIfNeeded().forPath(path, seedValue);
        }
        catch ( KeeperException.NodeExistsException ignore )
        {
            // ignore
        }

        readValue();
    }

    @Override
    public void close() throws IOException
    {
        client.getConnectionStateListenable().removeListener(connectionStateListener);
        state.set(State.CLOSED);
        listeners.clear();
    }

    private synchronized void readValue() throws Exception
    {
        Stat localStat = new Stat();
        byte[] bytes = client.getData().storingStatIn(localStat).usingWatcher(watcher).forPath(path);
        currentValue.set(new VersionedValue<byte[]>(localStat.getVersion(), Arrays.copyOf(bytes, bytes.length)));
    }

    private void notifyListeners()
    {
        final byte[] localValue = getValue();
        listeners.forEach
            (
                new Function<SharedValueListener, Void>()
                {
                    @Override
                    public Void apply(SharedValueListener listener)
                    {
                        try
                        {
                            listener.valueHasChanged(SharedValue.this, localValue);
                        }
                        catch ( Exception e )
                        {
                            log.error("From SharedValue listener", e);
                        }
                        return null;
                    }
                }
            );
    }

    private void notifyListenerOfStateChanged(final ConnectionState newState)
    {
        listeners.forEach
            (
                new Function<SharedValueListener, Void>()
                {
                    @Override
                    public Void apply(SharedValueListener listener)
                    {
                        listener.stateChanged(client, newState);
                        return null;
                    }
                }
            );
    }
}
