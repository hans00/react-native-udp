package com.tradle.react;

import android.content.Context;
import android.net.wifi.WifiManager;

import android.util.SparseArray;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The NativeModule in charge of storing active {@link UdpSocketClient}s, and acting as an api layer.
 */
public final class UdpSockets extends UdpSocketsSpec
        implements UdpSocketClient.OnDataReceivedListener, UdpSocketClient.OnRuntimeExceptionListener {
    public static final String TAG = "UdpSockets";
    private static final int N_THREADS = 2;

    private WifiManager.MulticastLock mMulticastLock;
    private final SparseArray<UdpSocketClient> mClients = new SparseArray<>();
    private final ExecutorService executorService = Executors.newFixedThreadPool(N_THREADS);

    public UdpSockets(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Nonnull
    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void onCatalystInstanceDestroy() {
        executorService.execute(new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < mClients.size(); i++) {
                    UdpSocketClient client = mClients.valueAt(i);
                    client.close();
                    if (mMulticastLock != null && mMulticastLock.isHeld() && client.isMulticast()) {
                        // drop the multi-cast lock if this is a multi-cast client
                        mMulticastLock.release();
                    }
                }
                mClients.clear();
            }
        }));
    }

    /**
     * Private method to retrieve clients.
     */
    private UdpSocketClient findClient(final double cId, final Callback callback) {
        final UdpSocketClient client = mClients.get((int)cId);
        if (client == null) {
            if (callback == null) {
                FLog.e(TAG, "missing callback parameter.");
            } else {
                callback.invoke(UdpErrorUtil.getError(UdpErrorCodes.clientNotFound.name(), "no client found with id " + cId), null);
            }
        }

        return client;
    }

    /**
     * Creates a {@link UdpSocketClient} with the given ID, and options
     */
    @Override
    @ReactMethod
    public void createSocket(final double cId, final ReadableMap options) {
        UdpSocketClient client = mClients.get((int)cId);
        if (client != null) {
            FLog.e(TAG, "createSocket called twice with the same id.");
            return;
        }
        mClients.put((int)cId, new UdpSocketClient(this, this));
    }

    /**
     * Binds to a given port and address, and begins listening for data.
     */
    @Override
    @ReactMethod
    public void bind(final double cId, final double port, final @Nullable String address, final @Nullable ReadableMap options,
                     final Callback callback) {
        executorService.execute(new Thread(new Runnable() {
            @Override
            public void run() {
                UdpSocketClient client = findClient(cId, callback);
                if (client == null) {
                    return;
                }

                try {
                    client.bind((int)port, address);

                    WritableMap result = Arguments.createMap();
                    result.putString("address", address);
                    result.putInt("port", (int)port);

                    callback.invoke(null, result);
                } catch (Exception e) {
                    // Socket is already bound or a problem occurred during binding
                    callback.invoke(UdpErrorUtil.getError(UdpErrorCodes.socketAlreadyBoundError.name(), e.getMessage()));
                }
            }
        }));
    }

    /**
     * Joins a multi-cast group
     */
    @Override
    @SuppressWarnings("unused")
    @ReactMethod
    public void addMembership(final double cId, final String multicastAddress) {
        executorService.execute(new Thread(new Runnable() {
            @Override
            public void run() {
                UdpSocketClient client = findClient(cId, null);
                if (client == null) {
                    return;
                }

                if (mMulticastLock == null) {
                    WifiManager wifiMgr = (WifiManager) getReactApplicationContext()
                            .getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
                    mMulticastLock = wifiMgr.createMulticastLock("react-native-udp");
                    mMulticastLock.setReferenceCounted(true);
                }

                try {
                    mMulticastLock.acquire();
                    client.addMembership(multicastAddress);
                } catch (IllegalStateException ise) {
                    // an exception occurred
                    if (mMulticastLock != null && mMulticastLock.isHeld()) {
                        mMulticastLock.release();
                    }
                    FLog.e(TAG, "addMembership", ise);
                } catch (UnknownHostException uhe) {
                    // an exception occurred
                    if (mMulticastLock != null && mMulticastLock.isHeld()) {
                        mMulticastLock.release();
                    }
                    FLog.e(TAG, "addMembership", uhe);
                } catch (IOException ioe) {
                    // an exception occurred
                    if (mMulticastLock != null && mMulticastLock.isHeld()) {
                        mMulticastLock.release();
                    }
                    FLog.e(TAG, "addMembership", ioe);
                }
            }
        }));
    }

    /**
     * Leaves a multi-cast group
     */
    @Override
    @ReactMethod
    public void dropMembership(final double cId, final String multicastAddress) {
        executorService.execute(new Thread(new Runnable() {
            @Override
            public void run() {
                UdpSocketClient client = findClient(cId, null);
                if (client == null) {
                    return;
                }

                try {
                    client.dropMembership(multicastAddress);
                } catch (IOException ioe) {
                    // an exception occurred
                    FLog.e(TAG, "dropMembership", ioe);
                } finally {
                    if (mMulticastLock != null && mMulticastLock.isHeld()) {
                        mMulticastLock.release();
                    }
                }
            }
        }));
    }

    /**
     * Sends udp data via the {@link UdpSocketClient}
     */
    @Override
    @ReactMethod
    public void send(final double cId, final String base64String,
                     final double port, final String address, final Callback callback) {
        executorService.execute(new Thread(new Runnable() {
            @Override
            public void run() {
                UdpSocketClient client = findClient(cId, callback);
                if (client == null) {
                    return;
                }

                try {
                    client.send(base64String, (int)port, address, callback);
                } catch (Exception exception) {
                    callback.invoke((UdpErrorUtil.getError(UdpErrorCodes.sendError.name(), exception.getMessage())));
                }
            }
        }));
    }

    /**
     * Closes a specific client's socket, and removes it from the list of known clients.
     */
    @Override
    @ReactMethod
    public void close(final double cId, final Callback callback) {
        executorService.execute(new Thread(new Runnable() {
            @Override
            public void run() {
                UdpSocketClient client = findClient(cId, callback);
                if (client == null) {
                    return;
                }

                if (mMulticastLock != null && mMulticastLock.isHeld() && client.isMulticast()) {
                    // drop the multi-cast lock if this is a multi-cast client
                    mMulticastLock.release();
                }
                client.close();
                callback.invoke();
                mClients.remove((int)cId);
            }
        }));
    }

    /**
     * Sets the broadcast flag for a given client.
     */
    @Override
    @ReactMethod
    public void setBroadcast(final double cId, final boolean flag, final Callback callback) {
        executorService.execute(new Thread(new Runnable() {
            @Override
            public void run() {
                UdpSocketClient client = findClient(cId, callback);
                if (client == null) {
                    return;
                }

                try {
                    client.setBroadcast(flag);
                    callback.invoke();
                } catch (SocketException e) {
                    callback.invoke(UdpErrorUtil.getError(UdpErrorCodes.setBroadcast.name(), e.getMessage()));
                }
            }
        }));
    }

    @Override
    public void addListener(String eventName) {}

    @Override
    public void removeListeners(double count) {}

    /**
     * Notifies the javascript layer upon data receipt.
     */
    @Override
    public void didReceiveData(final UdpSocketClient socket, final String data, final String host, final int port) {
        final long ts = System.currentTimeMillis();
        executorService.execute(new Thread(new Runnable() {
            @Override
            public void run() {
                int clientID = -1;
                for (int i = 0; i < mClients.size(); i++) {
                    clientID = mClients.keyAt(i);
                    // get the object by the key.
                    if (socket.equals(mClients.get(clientID))) {
                        break;
                    }
                }

                if (clientID == -1) {
                    return;
                }

                WritableMap eventParams = Arguments.createMap();
                eventParams.putInt("id", clientID);
                eventParams.putString("data", data);
                eventParams.putString("address", host);
                eventParams.putInt("port", port);
                // Use string for ts since it's 64 bits and putInt is only 32
                eventParams.putString("ts", Long.toString(ts));

                ReactContext reactContext = UdpSockets.this.getReactApplicationContext();
                reactContext
                        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                        .emit("UdpSocketMessage", eventParams);
            }
        }));
    }

    /**
     * Logs an error that happened during or prior to data reception.
     */
    @Override
    public void didReceiveError(UdpSocketClient client, String message) {
        FLog.e(TAG, message);
    }

    /**
     * Sends RuntimeExceptions to the application context handler.
     */
    @Override
    public void didReceiveException(RuntimeException exception) {
        getReactApplicationContext().handleException(exception);
    }
}
