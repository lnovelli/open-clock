package com.openclock;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Stable, synchronous, raw Binder protocol (no caller-supplied identity). */
public final class AccessService extends Service {
    public static final String DESCRIPTOR="com.openclock.IAccessApi";
    public static final int TRANSACTION_EXECUTE=IBinder.FIRST_CALL_TRANSACTION;
    // Do not let untrusted Binder calls queue behind authorization/storage work.
    private static final java.util.concurrent.Semaphore ADMISSION=new java.util.concurrent.Semaphore(1);
    private final Binder binder=new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if(code==INTERFACE_TRANSACTION) { if(reply!=null) reply.writeString(DESCRIPTOR); return true; }
            if(code!=TRANSACTION_EXECUTE) return super.onTransact(code,data,reply,flags);
            if(reply==null || (flags & IBinder.FLAG_ONEWAY)!=0) return false;
            int uid=Binder.getCallingUid(); // capture before clearing Binder identity
            if(!ADMISSION.tryAcquire()) {
                reply.writeNoException(); reply.writeString(IntentApi.denied("ACCESS_BUSY","Access service busy; retry later")); return true;
            }
            String result;
            try {
                if(data.dataSize()>65536) throw new IllegalArgumentException();
                data.enforceInterface(DESCRIPTOR);
                String request=data.readString();
                if(data.dataAvail()!=0 || request==null || request.length()>16384) throw new IllegalArgumentException();
                long identity=Binder.clearCallingIdentity();
                try { result=AccessPolicy.authorize(AccessService.this,uid,request); }
                finally { Binder.restoreCallingIdentity(identity); }
            } catch(RuntimeException e) { result=IntentApi.denied("INVALID_ARGUMENT","Invalid Binder request"); }
            finally { ADMISSION.release(); }
            reply.writeNoException(); reply.writeString(result); return true;
        }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
}
