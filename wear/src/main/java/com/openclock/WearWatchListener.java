package com.openclock;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.WearableListenerService;

/** Only fetch current DataItems; never treat an event itself as a complete snapshot. */
public final class WearWatchListener extends WearableListenerService {
    @Override public void onDataChanged(DataEventBuffer events) {
        for(DataEvent e:events) {
            String path=e.getDataItem().getUri().getPath();
            if(path!=null&&path.startsWith("/openclock/v1/")) {
                PhoneSyncRepair.schedule(this,false);
                PhoneSync.fromListener(this,false); break;
            }
        }
    }
    @Override public void onMessageReceived(MessageEvent e) {
        PhoneSync.reply(this,e.getSourceNodeId(),e.getPath(),e.getData());
    }
    @Override public void onCapabilityChanged(CapabilityInfo info) {
        if ("openclock_phone_v1".equals(info.getName())) {
            PhoneSyncRepair.schedule(this,true);
            PhoneSync.fromListener(this,true);
        }
    }
}
