package io.reactivex.remote;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.reactivex.remote.internal.LocalEventListener;
import io.reactivex.remote.internal.RemoteDataType;
import io.reactivex.remote.internal.RemoteEventListener;
import io.reactivex.remote.internal.RemoteEventListener_Proxy;
import io.reactivex.remote.internal.RemoteEventManager;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;

/**
 * Use this class to send the data at the server side that needs to
 * be delivered to the {@link rx.Observable} at the client side.
 * <p>
 * To send a event, use {@link #sendEvent(Object)}
 * <p>
 * Use {@link #sendCompleted()} to inform the client {@link rx.Observable} that
 * the data stream is complete
 *
 * @param <T> Supported types are {@link String}, {@link Byte}, {@link Short}, {@link Integer}, {@link Long},
 *            {@link Float}, {@link Double}, {@link Boolean}, {@link Parcelable},
 *            or any class annotated with <a href=\"https://github.com/johncarl81/parceler\">@Parcel</a>
 * @author js
 */
public class RemoteEventController<T> {

    private static final String TAG = "RemoteEventController";
    private boolean DEBUG = false;
    private boolean completed;
    private T lastEvent;
    private Exception lastException;
    private RemoteDataType dataType = RemoteDataType.UnKnown;
    private Object LOCK = new Object();
    private RemoteEventHandler remoteEventHandler = new RemoteEventHandler();
    private Class lastDataTypeClass;
    private RemoteDataType lastDataType;
    private Observable<T> sourceObservable;
    private Subscription sourceSubscription;


    /**
     * Create a default instance of {@link RemoteEventController}
     * Use {@link #sendEvent(Object)}, {@link #sendCompleted()}  to send the data
     */
    public RemoteEventController(){
    }

    /**
     * Creates an instance of {@link RemoteEventController} with the given {@link Observable}
     * @param observable The {@link Observable} to listen to
     */
    public RemoteEventController(Observable<T> observable){
        this.sourceObservable = observable;
    }


    public RemoteEventManager getRemoteEventManager() {
        return remoteEventHandler;
    }

    /**
     * Send the given data to the client observable
     *
     * @param data The data that needs to be send
     */
    public final void sendEvent(T data) {
        if (!completed) {
            synchronized (LOCK) {
                RemoteDataType dType = getDataType(data);
                if (dType != RemoteDataType.UnKnown) {
                    this.lastEvent = data;
                    this.dataType = dType;
                    remoteEventHandler.sendEventToObservable(lastEvent, dataType);
                } else {
                    Log.w(TAG, "Ignoring unsupported type " + data);
                }
            }
        }
    }

    /**
     * Generate an onCompleted event at the client observable.
     */
    public final void sendCompleted() {
        if (!completed) {
            synchronized (LOCK) {
                completed = true;
                remoteEventHandler.sendOnCompleted();
            }
        }
    }

    /**
     * Generate an onError event at the client observable.
     */
    public final void sendError(Exception exception) {
        if (!completed) {
            synchronized (LOCK) {
                lastException = exception;
                completed = true;
                remoteEventHandler.sendOnError(exception);
            }
        }
    }

    /**
     * Override this to know when <b>first</b> client subscribed to the observable
     */
    public void onSubscribed() {
        if (sourceObservable != null) {
            sourceSubscription = sourceObservable.subscribe(new Action1<T>() {
                @Override
                public void call(T t) {
                    sendEvent(t);
                }
            }, new Action1<Throwable>() {
                @Override
                public void call(Throwable throwable) {
                    sendError(new Exception(throwable));
                }
            }, new Action0() {
                @Override
                public void call() {
                    sendCompleted();
                }
            });
        }
    }

    /**
     * Override this to know when <b>ALL</b> clients have unsubscribed.
     */
    public void onUnSubscribed() {
        if (sourceSubscription != null) {
            sourceSubscription.unsubscribe();
            sourceSubscription = null;
        }
    }

    /**
     * Enable or disable debug prints. Disabled by default
     */
    public void setDebug(boolean enable) {
        DEBUG = enable;
    }


    /**
     * Returns what type of data this is
     */
    private RemoteDataType getDataType(Object data) {
        if (data != null) {
            if (lastDataTypeClass == data.getClass()) {
                return lastDataType;
            } else {
                lastDataTypeClass = data.getClass();
                lastDataType = findDataType(data);
                return lastDataType;
            }
        } else {
            return RemoteDataType.UnKnown;
        }
    }

    /**
     * Finds the type of data
     */
    private RemoteDataType findDataType(Object data) {
        if (data instanceof Byte) {
            return RemoteDataType.Byte;
        }
        if (data instanceof Short) {
            return RemoteDataType.Short;
        }
        if (data instanceof Integer) {
            return RemoteDataType.Integer;
        }
        if (data instanceof Long) {
            return RemoteDataType.Long;
        }
        if (data instanceof Float) {
            return RemoteDataType.Float;
        }
        if (data instanceof Double) {
            return RemoteDataType.Double;
        }
        if (data instanceof String) {
            return RemoteDataType.String;
        }
        if (data instanceof Character) {
            return RemoteDataType.Char;
        }
        if (data instanceof Boolean) {
            return RemoteDataType.Boolean;
        }
        if (data instanceof List) {
            return RemoteDataType.List;
        }
        if (data instanceof Parcelable) {
            return RemoteDataType.Parcelable;
        } else if (getParcelerClass(data) != null) {
            return RemoteDataType.Parceler;
        } else if (getRemoterBinder(data) != null) {
            return RemoteDataType.Remoter;
        } else {
            return RemoteDataType.UnKnown;
        }
    }


    /**
     * Writes the @Parcel data
     */
    private void writeParceler(Object data, Bundle bundle, String keyPrefix) throws Exception {
        Class parcelerClass = getParcelerClass(data);
        if (parcelerClass != null) {
            Class parcelClass = Class.forName(parcelerClass.getName() + "$$Parcelable");
            Constructor constructor = parcelClass.getConstructor(parcelerClass);
            Parcelable parcelable = (Parcelable) constructor.newInstance(data);
            bundle.putParcelable(RemoteEventManager.REMOTE_DATA_KEY + keyPrefix, parcelable);
        }
    }

    /**
     * Writes the @Remoter data
     */
    private void writeRemoter(Object data, Bundle bundle, String keyPrefix) throws Exception {
        Class remoterInterfaceClass = getRemoterBinder(data);
        if (remoterInterfaceClass != null) {
            Class remoterStubClass = Class.forName(remoterInterfaceClass.getName() + "_Stub");
            Constructor constructor = remoterStubClass.getConstructor(remoterInterfaceClass);
            IBinder binder = (IBinder) constructor.newInstance(data);
            bundle.putString(RemoteEventManager.REMOTE_DATA_EXTRA + keyPrefix, remoterInterfaceClass.getName());
            bundle.putBinder(RemoteEventManager.REMOTE_DATA_KEY + keyPrefix, binder);
        }
    }


    /**
     * Finds the parceler class type
     */
    private Class getParcelerClass(Object object) {
        Class objClass = object.getClass();
        boolean found = false;
        while (!found && objClass != null) {
            try {
                Class.forName(objClass.getName() + "$$Parcelable");
                found = true;
            } catch (ClassNotFoundException ignored) {
                objClass = objClass.getSuperclass();
            }
        }
        return objClass;
    }

    /**
     * Returns the remoter binder if it is of that type
     */
    private Class getRemoterBinder(Object object) {
        return getRemoterBinder(object.getClass());
    }

    /**
     * Returns the remoter binder if it is of that type
     */
    private Class getRemoterBinder(Class objClass) {
        Class remoterClass = null;
        if (objClass != null) {
            for (Class implementedInterface : objClass.getInterfaces()) {
                try {
                    Class.forName(implementedInterface.getName() + "_Stub");
                    remoterClass = implementedInterface;
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }
            if (remoterClass == null) {
                return getRemoterBinder(objClass.getSuperclass());
            }
        }
        return remoterClass;
    }

    class RemoteEventHandler implements RemoteEventManager {

        private RemoteEventListener listener;
        private IBinder.DeathRecipient deathRecipient;

        @Override
        public void subscribe(final RemoteEventListener listener) {
            if (DEBUG) {
                Log.v(TAG, "onSubscribe " + completed + " " + lastEvent);
            }
            synchronized (LOCK) {
                this.listener = listener;
                if (!completed) {
                    RemoteEventController.this.onSubscribed();
                    if (listener instanceof RemoteEventListener_Proxy) {
                        deathRecipient = new IBinder.DeathRecipient() {
                            @Override
                            public void binderDied() {
                                if (DEBUG) {
                                    Log.v(TAG, "Binder dead");
                                }
                                unsubscribe();
                            }
                        };
                        ((RemoteEventListener_Proxy) listener).linkToDeath(deathRecipient);
                    }
                }
                if (lastEvent != null) {
                    sendEventToObservable(lastEvent, dataType);
                }
                if (lastException != null) {
                    sendOnError(lastException);
                } else if (completed) {
                    sendOnCompleted();
                }
            }
        }

        @Override
        public void unsubscribe() {
            if (listener != null) {
                if (DEBUG) {
                    Log.v(TAG, "on unsubscribe" + lastEvent);
                }
                RemoteEventController.this.onUnSubscribed();
                if (listener instanceof RemoteEventListener_Proxy) {
                    ((RemoteEventListener_Proxy) listener).unLinkToDeath(deathRecipient);
                }
                listener = null;
                deathRecipient = null;
            }
        }


        /**
         * Sends the data to observable
         */
        void sendEventToObservable(T data, RemoteDataType dataType) {
            try {
                if (DEBUG) {
                    Log.v(TAG, "Sending event" + listener + " " + data);
                }

                if (this.listener != null) {
                    if (listener instanceof LocalEventListener) {
                        ((LocalEventListener) listener).onLocalEvent(data);
                    } else {
                        Bundle remoteData = new Bundle();
                        addDataToBundle(remoteData, data, dataType, "");
                        listener.onRemoteEvent(remoteData);
                    }
                }
            } catch (Exception ex) {
                if (!completed) {
                    completed = true;
                    onUnSubscribed();
                }
            }
        }

        private void addDataToBundle(Bundle remoteData, Object data, RemoteDataType dataType, String keyPrefix) throws Exception {
            remoteData.putString(RemoteEventManager.REMOTE_DATA_TYPE + keyPrefix, dataType.name());
            switch (dataType) {
                case List:
                    List listData = (List) data;
                    int dataSize = listData != null ? listData.size() : 0;
                    remoteData.putInt(RemoteEventManager.REMOTE_DATA_LIST_SIZE + keyPrefix, dataSize);
                    RemoteDataType itemDataType = null;
                    for (int i = 0; i < dataSize; i++) {
                        Object item = listData.get(i);
                        if (itemDataType == null) {
                            itemDataType = findDataType(item);
                        }
                        addDataToBundle(remoteData, item, itemDataType, keyPrefix + i);
                    }
                    break;

                case Parcelable:
                    remoteData.putParcelable(RemoteEventManager.REMOTE_DATA_KEY + keyPrefix, (Parcelable) data);
                    break;
                case Parceler:
                    writeParceler(data, remoteData, keyPrefix);
                    break;
                case Remoter:
                    writeRemoter(data, remoteData, keyPrefix);
                    break;
                case Byte:
                    remoteData.putByte(RemoteEventManager.REMOTE_DATA_KEY + keyPrefix, (Byte) data);
                    break;
                case Short:
                    remoteData.putShort(RemoteEventManager.REMOTE_DATA_KEY + keyPrefix, (Short) data);
                    break;
                case Integer:
                    remoteData.putInt(RemoteEventManager.REMOTE_DATA_KEY + keyPrefix, (Integer) data);
                    break;
                case Float:
                    remoteData.putFloat(RemoteEventManager.REMOTE_DATA_KEY + keyPrefix, (Float) data);
                    break;
                case Double:
                    remoteData.putDouble(RemoteEventManager.REMOTE_DATA_KEY + keyPrefix, (Double) data);
                    break;
                case String:
                    remoteData.putString(RemoteEventManager.REMOTE_DATA_KEY + keyPrefix, (String) data);
                    break;
                case Char:
                    remoteData.putChar(RemoteEventManager.REMOTE_DATA_KEY + keyPrefix, (Character) data);
                    break;
                case Long:
                    remoteData.putLong(RemoteEventManager.REMOTE_DATA_KEY + keyPrefix, (Long) data);
                    break;
                case Boolean:
                    remoteData.putInt(RemoteEventManager.REMOTE_DATA_KEY + keyPrefix, ((Boolean) data).booleanValue() ? 1 : 0);
                    break;
                case UnKnown:
                    Log.w(TAG, "Ignoring unsupported type " + data);
                    break;

            }
        }

        /**
         * Send oncompleted
         */
        void sendOnCompleted() {
            try {
                if (DEBUG) {
                    Log.v(TAG, "Sending complete" + listener);
                }

                if (this.listener != null) {
                    listener.onCompleted();
                    this.listener = null;
                }
            } catch (Exception ex) {
                if (!completed) {
                    completed = true;
                    onUnSubscribed();
                }
            }
        }

        /**
         * Send oncompleted
         */
        void sendOnError(Exception exception) {
            try {
                if (DEBUG) {
                    Log.v(TAG, "Sending onError" + listener);
                }

                if (this.listener != null) {
                    listener.onError(exception);
                    this.listener = null;
                }
            } catch (Exception ex) {
                if (!completed) {
                    completed = true;
                    onUnSubscribed();
                }
            }
        }

    }

}
