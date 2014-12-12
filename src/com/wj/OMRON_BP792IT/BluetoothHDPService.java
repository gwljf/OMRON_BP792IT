package com.wj.OMRON_BP792IT;

import android.app.Service;
import android.bluetooth.*;
import android.content.Intent;
import android.os.*;
import android.util.Log;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Created by wlf on 12/4/14.
 */
public class BluetoothHDPService extends Service {
    private static final String TAG = "BluetoothHDPService";

    public static final int RESULT_OK = 0;
    public static final int RESULT_FAIL = -1;

    // Status codes sent back to the UI client.
    // Application registration complete.
    public static final int STATUS_HEALTH_APP_REG = 100;
    // Application unregistration complete.
    public static final int STATUS_HEALTH_APP_UNREG = 101;
    // Channel creation complete.
    public static final int STATUS_CREATE_CHANNEL = 102;
    // Channel destroy complete.
    public static final int STATUS_DESTROY_CHANNEL = 103;
    // Reading data from Bluetooth HDP device.
    public static final int STATUS_READ_DATA = 104;
    // Done with reading data.
    public static final int STATUS_READ_DATA_DONE = 105;

    // Message codes received from the UI client.
    // Register client with this service.
    public static final int MSG_REG_CLIENT = 200;
    // Unregister client from this service.
    public static final int MSG_UNREG_CLIENT = 201;
    // Register health application.
    public static final int MSG_REG_HEALTH_APP = 300;
    // Unregister health application.
    public static final int MSG_UNREG_HEALTH_APP = 301;
    // Connect channel.
    public static final int MSG_CONNECT_CHANNEL = 400;
    // Disconnect channel.
    public static final int MSG_DISCONNECT_CHANNEL = 401;

    public static final int RECEIVED_SYS = 901;

    public static final int RECEIVED_DIA = 902;

    public static final int RECEIVED_PUL = 903;


    private BluetoothHealthAppConfiguration mHealthAppConfig;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothHealth mBluetoothHealth;
    private BluetoothDevice mDevice;
    private int mChannelId;

    private Messenger mClient;

    private int count;
    private byte[] invoke = {0x00, 0x00};

    // Handles events sent by {@link HealthHDPActivity}.
    private class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                // Register UI client to this service so the client can receive messages.
                case MSG_REG_CLIENT:
                    Log.d(TAG, "Activity client registered");
                    mClient = msg.replyTo;
                    break;
                // Unregister UI client from this service.
                case MSG_UNREG_CLIENT:
                    mClient = null;
                    break;
                // Register health application.
                case MSG_REG_HEALTH_APP:
                    registerApp(msg.arg1);
                    break;
                // Unregister health application.
                case MSG_UNREG_HEALTH_APP:
                    unregisterApp();
                    break;
                // Connect channel.
                case MSG_CONNECT_CHANNEL:
                    mDevice = (BluetoothDevice) msg.obj;
                    connectChannel();
                    break;
                // Disconnect channel.
                case MSG_DISCONNECT_CHANNEL:
                    mDevice = (BluetoothDevice) msg.obj;
                    disconnectChannel();
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    final Messenger mMessenger = new Messenger(new IncomingHandler());

    /**
     * Make sure Bluetooth and health profile are available on the Android device.  Stop service
     * if they are not available.
     */
    @Override
    public void onCreate() {
        super.onCreate();

        Log.e("TEST", "HDPService Created");

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            // Bluetooth adapter isn't available.  The client of the service is supposed to
            // verify that it is available and activate before invoking this service.
            stopSelf();
            return;
        }
        if (!mBluetoothAdapter.getProfileProxy(this, mBluetoothServiceListener,
                BluetoothProfile.HEALTH)) {
            Toast.makeText(this, "bluetooth_health_profile_not_available",
                    Toast.LENGTH_LONG);
            stopSelf();
            return;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "BluetoothHDPService is running.");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    };

    // Register health application through the Bluetooth Health API.
    private void registerApp(int dataType) {
        Log.e(TAG, "registerApp()");
        mBluetoothHealth.registerSinkAppConfiguration(TAG, dataType, mHealthCallback);
    }

    // Unregister health application through the Bluetooth Health API.
    private void unregisterApp() {
        Log.e(TAG, "unregisterApp()");
        mBluetoothHealth.unregisterAppConfiguration(mHealthAppConfig);
    }

    // Connect channel through the Bluetooth Health API.
    private void connectChannel() {
        Log.i(TAG, "connectChannel()");
        mBluetoothHealth.connectChannelToSource(mDevice, mHealthAppConfig);
    }

    // Disconnect channel through the Bluetooth Health API.
    private void disconnectChannel() {
        Log.i(TAG, "disconnectChannel()");
        mBluetoothHealth.disconnectChannel(mDevice, mHealthAppConfig, mChannelId);
    }

    // Callbacks to handle connection set up and disconnection clean up.
    private final BluetoothProfile.ServiceListener mBluetoothServiceListener =
            new BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.HEALTH) {
                        mBluetoothHealth = (BluetoothHealth) proxy;
                        if (Log.isLoggable(TAG, Log.DEBUG))
                            Log.d(TAG, "onServiceConnected to profile: " + profile);
                    }
                }

                public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.HEALTH) {
                        mBluetoothHealth = null;
                    }
                }
            };

    private final BluetoothHealthCallback mHealthCallback = new BluetoothHealthCallback() {
        // Callback to handle application registration and unregistration events.  The service
        // passes the status back to the UI client.
        public void onHealthAppConfigurationStatusChange(BluetoothHealthAppConfiguration config,
                                                         int status) {
            if (status == BluetoothHealth.APP_CONFIG_REGISTRATION_FAILURE) {
                mHealthAppConfig = null;
                sendMessage(STATUS_HEALTH_APP_REG, RESULT_FAIL);
            } else if (status == BluetoothHealth.APP_CONFIG_REGISTRATION_SUCCESS) {
                mHealthAppConfig = config;
                sendMessage(STATUS_HEALTH_APP_REG, RESULT_OK);
            } else if (status == BluetoothHealth.APP_CONFIG_UNREGISTRATION_FAILURE ||
                    status == BluetoothHealth.APP_CONFIG_UNREGISTRATION_SUCCESS) {
                sendMessage(STATUS_HEALTH_APP_UNREG,
                        status == BluetoothHealth.APP_CONFIG_UNREGISTRATION_SUCCESS ?
                                RESULT_OK : RESULT_FAIL);
            }
        }

        // Callback to handle channel connection state changes.
        // Note that the logic of the state machine may need to be modified based on the HDP device.
        // When the HDP device is connected, the received file descriptor is passed to the
        // ReadThread to read the content.
        public void onHealthChannelStateChange(BluetoothHealthAppConfiguration config,
                                               BluetoothDevice device, int prevState, int newState, ParcelFileDescriptor fd,
                                               int channelId) {

            Log.e("Read Thread", "Start If Statements");
            Log.e("Read Thread", "prevState: " + prevState);
            Log.e("Read Thread", "newState: " + newState);

            if (Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, String.format("prevState\t%d ----------> newState\t%d",
                        prevState, newState));

//            if (prevState != BluetoothHealth.STATE_CHANNEL_CONNECTED &&
//                    newState == BluetoothHealth.STATE_CHANNEL_CONNECTED) {

            if (prevState == BluetoothHealth.STATE_CHANNEL_DISCONNECTED &&
                    newState == BluetoothHealth.STATE_CHANNEL_CONNECTED) {
                if (config.equals(mHealthAppConfig)) {
                    mChannelId = channelId;
                    sendMessage(STATUS_CREATE_CHANNEL, RESULT_OK);
                    Log.e("Read Thread", "Read  Start 1");
                    (new ReadThread(fd)).start();
                } else {
                    Log.e("Read Thread", "Status Create Channel Fail 1");
                    sendMessage(STATUS_CREATE_CHANNEL, RESULT_FAIL);
                }
            } else if (prevState == BluetoothHealth.STATE_CHANNEL_CONNECTING &&
                    newState == BluetoothHealth.STATE_CHANNEL_DISCONNECTED) {
                sendMessage(STATUS_CREATE_CHANNEL, RESULT_FAIL);
                Log.e("Read Thread", "Status Create Channel Fail 2");
            } else if (newState == BluetoothHealth.STATE_CHANNEL_DISCONNECTED) {
                if (config.equals(mHealthAppConfig)) {
                    sendMessage(STATUS_DESTROY_CHANNEL, RESULT_OK);
                    Log.e("Read Thread", "Status Disconnect OK");
                } else {
                    sendMessage(STATUS_DESTROY_CHANNEL, RESULT_FAIL);
                    Log.e("Read Thread", "Status Disconnect FAIL");
                }
            }else if (prevState == BluetoothHealth.STATE_CHANNEL_CONNECTING && newState == BluetoothHealth.STATE_CHANNEL_CONNECTED) {
                if (config.equals(mHealthAppConfig)) {
                    mChannelId = channelId;
                    sendMessage(STATUS_CREATE_CHANNEL, RESULT_OK);
                    Log.e("Read Thread", "Read  Start 2");
                    (new ReadThread(fd)).start();
                } else {
                    sendMessage(STATUS_CREATE_CHANNEL, RESULT_FAIL);
                    Log.e("Read Thread", "Status Create Channel Fail 3");
                }
            }
        }
    };

    // Sends an update message to registered UI client.
    private void sendMessage(int what, int value) {
        if (mClient == null) {
            Log.d(TAG, "No clients registered.");
            return;
        }

        try {
            mClient.send(Message.obtain(null, what, value, 0));
        } catch (RemoteException e) {
            // Unable to reach client.
            e.printStackTrace();
        }
    }

    public String byte2hex(byte[] b){
        // String Buffer can be used instead
        String hs = "";
        String stmp = "";

        for (int n = 0; n < b.length; n++){
            stmp = (java.lang.Integer.toHexString(b[n] & 0XFF));

            if (stmp.length() == 1){
                hs = hs + "0" + stmp;
            }
            else{
                hs = hs + stmp;
            }
            if (n < b.length - 1){
                hs = hs + "";
            }
        }

        return hs;
    }

    public static int byteToUnsignedInt(byte b) {
        return 0x00 << 24 | b & 0xff;
    }

    public static int toInt(byte[] bytes) {
        int ret = 0;
        for (int i=0; i<4 && i<bytes.length; i++) {
            ret <<= 8;
            ret |= (int)bytes[i] & 0xFF;
        }
        return ret;
    }

    // Thread to read incoming data received from the HDP device.  This sample application merely
    // reads the raw byte from the incoming file descriptor.  The data should be interpreted using
    // a health manager which implements the IEEE 11073-xxxxx specifications.

    private class ReadThread extends Thread {
        private ParcelFileDescriptor mFd;

        public ReadThread(ParcelFileDescriptor fd) {
            super();
            mFd = fd;
        }

        @Override
        public void run() {
            FileInputStream fis = new FileInputStream(mFd.getFileDescriptor());
            final byte data[] = new byte[600];
            try {
                while(fis.read(data) > -1) {
                    // At this point, the application can pass the raw data to a parser that
                    // has implemented the IEEE 11073-xxxxx specifications.  Instead, this sample
                    // simply indicates that some data has been received
                //    Log.e("Test", data.toString());

                    if (data[0] != (byte) 0x00)
                    {
                        String test = byte2hex(data);
                        Log.i(TAG, test);
                        if(data[0] == (byte) 0xE2){
                            Log.i(TAG, "E2");
                            count = 1;

                            (new WriteThread(mFd)).start();
                            try {
                                sleep(100);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            Log.i(TAG, "Seconds Write Thread");
                            count = 2;
                            (new WriteThread(mFd)).start();

                        }
                        else if (data[0] == (byte)0xE7){
                            Log.i(TAG, "E7");

                            if(data[18]==(byte)0x0d && data[19]==(byte)0x1f){


                                byte[] weight = {data[42],data[43]};
                                Log.i(TAG, " weight - "+(float)toInt(weight)/10);


                                byte[] data2 = {data[58],data[59]};
                                Log.i(TAG, " data2 - "+toInt(data2));


                                byte[] BMI = {data[74],data[75]};
                                Log.i(TAG, " BMI - "+(float)toInt(BMI)/10);


                                byte[] BodyFat = {data[90],data[91]};
                                Log.i(TAG, " BodyFat - "+(float)toInt(BodyFat)/10/100);


                                byte[] Resting_Metabolism = {data[106],data[107]};
                                Log.i(TAG, " Resting_Metabolism - "+toInt(Resting_Metabolism));


                                byte[] Visceral_Fat = {data[121]};
                                Log.i(TAG, " Visceral_Fat - "+toInt(Visceral_Fat)/10);


                                byte[] Body_Age = {data[134],data[135]};
                                Log.i(TAG, " Body_Age - "+toInt(Body_Age));


                                byte[] data9 = {data[172],data[173]};
                                Log.i(TAG, " data9 - "+toInt(data9));


                            }



                        }
                        else if (data[0] == (byte) 0xE4)
                        {

                            count = 4;
                            (new WriteThread(mFd)).start();
                        }
                        //zero out the data
                        for (int i = 0; i < data.length; i++){
                            data[i] = (byte) 0x00;
                        }
                    }
                    sendMessage(STATUS_READ_DATA, 0);
                }
            } catch(IOException ioe) {}
            if (mFd != null) {
                try {
                    mFd.close();
                } catch (IOException e) { /* Do nothing. */ }
            }
            sendMessage(STATUS_READ_DATA_DONE, 0);
        }
    }

    private class WriteThread extends Thread {
        private ParcelFileDescriptor mFd;

        public WriteThread(ParcelFileDescriptor fd) {
            super();
            mFd = fd;
        }

        @Override
        public void run() {
            FileOutputStream fos = new FileOutputStream(mFd.getFileDescriptor());
            final byte data_AR[] = new byte[]{
                    (byte) 0xE3, (byte) 0x00,
                    (byte) 0x00, (byte) 0x2C,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x50, (byte) 0x79,
                    (byte) 0x00, (byte) 0x26,
                    (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x80, (byte) 0x00,
                    (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x08,  //bt add for phone, can be automate in the future
//                    (byte) 0x3C, (byte) 0x5A, (byte) 0x37, (byte) 0xFF,
//                    (byte) 0xFE, (byte) 0x95, (byte) 0xEE, (byte) 0xE3,
                    (byte) 0x00, (byte) 0x22, (byte) 0x09, (byte) 0x22, (byte) 0x58, (byte) 0x07, (byte) 0x76, (byte) 0xa8,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00

//                    (byte) 0xE3, (byte) 0x00, //APDU CHOICE Type (AareApdu)
//                    (byte) 0x00, (byte) 0x2C, //CHOICE.length = 44
//                    (byte) 0x00, (byte) 0x00,  //result = accepted
//                    (byte) 0x50, (byte) 0x79,  //data-proto-id = 20601
//                    (byte) 0x00, (byte) 0x26,  //data-proto-info length = 38
//                    (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0x00, // protocolVersion
//                    (byte) 0x80, (byte) 0x00,   // encoding rules = MDER
//                    (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0x00, // nomenclatureVersion
//                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, // functionalUnits, normal Association
//                    (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0x00, // systemType = sys-type-manager
//                    (byte) 0x00, (byte) 0x08,   // system-id length = 8 and value (manufacturer- and device- specific)
//             //       (byte) 0x88, (byte) 0x77, (byte) 0x66, (byte) 0x55, (byte) 0x44, (byte) 0x33, (byte) 0x22, (byte) 0x11,
//                    (byte) 0x00, (byte) 0x22, (byte) 0x09, (byte) 0x22, (byte) 0x58, (byte) 0x07, (byte) 0x76, (byte) 0xa8,
//                    (byte) 0x00, (byte) 0x00,   // Manager’s response to config-id is always 0
//                    (byte) 0x00, (byte) 0x00,   // Manager’s response to data-req-mode-flags is always 0
//                    (byte) 0x00, (byte) 0x00,   // data-req-init-agent-count and data-req-init-manager-count are always 0
//                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00   // optionList.count = 0 | optionList.length = 0
            };
            final byte data_DR[] = new byte[]{
                    (byte) 0xE7, (byte) 0x00,
                    (byte) 0x00, (byte) 0x12,
                    (byte) 0x00, (byte) 0x10,
                    (byte) 0x00, (byte) 0x01,
                    (byte) 0x02, (byte) 0x01,
                    (byte) 0x00, (byte) 0x0A,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x0D, (byte) 0x1D,
                    (byte) 0x00, (byte) 0x00};

            final byte data_CF[] = new byte[]{
                    (byte) 0xE7, (byte) 0x00,
                    (byte) 0x00, (byte) 0x16,
                    (byte) 0x00, (byte) 0x14,
                    (byte) 0x00, (byte) 0x01,
            //        (byte) invoke[0], (byte) invoke[1],
            //        (byte) 0xaa, (byte) 0xaa,


                    (byte) 0x02, (byte) 0x01,
                    (byte) 0x00, (byte) 0x0E,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x0D, (byte) 0x1C,
                    (byte) 0x00, (byte) 0x04,
             //       (byte) 0x40, (byte) 0x01,
                    (byte) 0x40, (byte) 0x01,
                    (byte) 0x00, (byte) 0x00
            };


            final byte get_MDS[] = new byte[]{
                    (byte) 0xE7, (byte) 0x00,
                    (byte) 0x00, (byte) 0x0E,
                    (byte) 0x00, (byte) 0x0C,
                    (byte) 0x34, (byte) 0x56,
            //        (byte) invoke[0], (byte) invoke[1],
                    (byte) 0x01, (byte) 0x03,
                    (byte) 0x00, (byte) 0x06,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00};


            final byte data_RR[] = new byte[]{(byte) 0xE5, (byte) 0x00,
                    (byte) 0x00, (byte) 0x02,
                    (byte) 0x00, (byte) 0x00};

            final byte data_RRQ[] = new byte[]{
                    (byte) 0xE4, (byte) 0x00,
                    (byte) 0x00, (byte) 0x02,
                    (byte) 0x00, (byte) 0x00};

            final byte data_ABORT[] = new byte[]{(byte) 0xE6, (byte) 0x00,
                    (byte) 0x00, (byte) 0x02,
                    (byte) 0x00, (byte) 0x00};

            final byte data_REQUEST[] = new byte[]{
                    (byte) 0xE7, (byte) 0x00,
                    (byte) 0x00, (byte) 0x1E,
                    (byte) 0x00, (byte) 0x1C,
                    (byte) 0x76, (byte) 0x54,
                    (byte) 0x01, (byte) 0x07,
                    (byte) 0x00, (byte) 0x16,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x0C, (byte) 0x1B,
                    (byte) 0x00, (byte) 0x10,
                    (byte) 0x01, (byte) 0x00,
                    (byte) 0x84, (byte) 0x80,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x06,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00
            };

            try {
                Log.i(TAG, String.valueOf(count));
                if (count == 1) {
                    fos.write(data_AR);
                    Log.i(TAG, "Association Responded!");
                } else if (count == 2) {
                    fos.write(get_MDS);
                    Log.i(TAG, "Get MDS object attributes!");
                } else if (count == 3) {
                    fos.write(data_DR);
                    Log.i(TAG, "Data Responsed!");
                } else if (count == 4) {
                    fos.write(data_RR);
                    Log.i(TAG, "Data Released!");
                } else if (count == 5) {
                    fos.write(data_CF);
                    Log.i(TAG, "Data configured!");
                } else if (count == 6) {
                    fos.write(data_REQUEST);
                    Log.i(TAG, "Data requested!");
                }
            } catch (IOException ioe) {
            }
        }
    }
}