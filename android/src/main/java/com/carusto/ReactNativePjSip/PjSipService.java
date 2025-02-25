package com.carusto.ReactNativePjSip;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.carusto.ReactNativePjSip.dto.AccountConfigurationDTO;
import com.carusto.ReactNativePjSip.dto.CallSettingsDTO;
import com.carusto.ReactNativePjSip.dto.ServiceConfigurationDTO;
import com.carusto.ReactNativePjSip.dto.SipMessageDTO;
import com.carusto.ReactNativePjSip.utils.ArgumentUtils;

import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;

import org.json.JSONObject;
import org.pjsip.pjsua2.AccountConfig;
import org.pjsip.pjsua2.AudDevManager;
import org.pjsip.pjsua2.AuthCredInfo;
import org.pjsip.pjsua2.CallOpParam;
import org.pjsip.pjsua2.CallSetting;
import org.pjsip.pjsua2.CodecInfoVector2;
import org.pjsip.pjsua2.Endpoint;
import org.pjsip.pjsua2.EpConfig;
import org.pjsip.pjsua2.OnCallStateParam;
import org.pjsip.pjsua2.OnRegStartedParam;
import org.pjsip.pjsua2.OnRegStateParam;
import org.pjsip.pjsua2.SipHeader;
import org.pjsip.pjsua2.SipHeaderVector;
import org.pjsip.pjsua2.SipTxOption;
import org.pjsip.pjsua2.StringVector;
import org.pjsip.pjsua2.TransportConfig;
import org.pjsip.pjsua2.CodecInfoVector;
import org.pjsip.pjsua2.CodecInfo;
import org.pjsip.pjsua2.VideoDevInfo;
import org.pjsip.pjsua2.pj_qos_type;
import org.pjsip.pjsua2.pjmedia_orient;
import org.pjsip.pjsua2.pjsip_inv_state;
import org.pjsip.pjsua2.pjsip_status_code;
import org.pjsip.pjsua2.pjsip_transport_type_e;
import org.pjsip.pjsua2.pjmedia_srtp_use;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class PjSipService extends Service {

    private static final int FOREGROUND_SERVICE_ID = 1339;
    private static String TAG = "PjSipService";

    private boolean mInitialized;

    private HandlerThread mWorkerThread;

    private Handler mHandler;

    private Endpoint mEndpoint;

    private int mUdpTransportId;

    private int mTcpTransportId;

    private int mTlsTransportId;

    private ServiceConfigurationDTO mServiceConfiguration = new ServiceConfigurationDTO();

    private PjSipLogWriter mLogWriter;

    private static int NOTIFICATION_ID = -4568;

    private PjSipBroadcastEmiter mEmitter;

    private List<PjSipAccount> mAccounts = new ArrayList<>();

    private List<PjSipCall> mCalls = new ArrayList<>();

    private List<PjSipCall> pCalls = new ArrayList<>();

    private List<PjSipCall> cCalls = new ArrayList<>();

    // In order to ensure that GC will not destroy objects that are used in PJSIP
    // Also there is limitation of pjsip that thread should be registered first before working with library
    // (but we couldn't register GC thread in pjsip)
    private List<Object> mTrash = new LinkedList<>();

    private AudioManager mAudioManager;

    private boolean mUseSpeaker = false;

    private PowerManager mPowerManager;

    private PowerManager.WakeLock mIncallWakeLock;

    // private TelephonyManager mTelephonyManager;

    private WifiManager mWifiManager;

    private WifiManager.WifiLock mWifiLock;

    // private boolean mGSMIdle;

    private BroadcastReceiver mPhoneStateChangedReceiver = new PhoneStateChangedReceiver();

    public PjSipBroadcastEmiter getEmitter() {
        return mEmitter;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void load() {
        // Load native libraries
        // try {
        //     System.loadLibrary("openh264");
        // } catch (UnsatisfiedLinkError error) {
        //     Log.e(TAG, "Error while loading OpenH264 native library", error);
        //     throw new RuntimeException(error);
        // }

        try {
            System.loadLibrary("pjsua2");
        } catch (UnsatisfiedLinkError error) {
            Log.e(TAG, "Error while loading PJSIP pjsua2 native library", error);
            throw new RuntimeException(error);
        }

        // Start stack
        try {
            mEndpoint = new Endpoint();
            mEndpoint.libCreate();
            StringVector servers = new StringVector();
            servers.add("stun.l.google.com:19302");
            mEndpoint.natUpdateStunServers(servers, true);

            // Configure endpoint
            EpConfig epConfig = new EpConfig();

            epConfig.getLogConfig().setLevel(10);
            epConfig.getLogConfig().setConsoleLevel(10);

            mLogWriter = new PjSipLogWriter();
            epConfig.getLogConfig().setWriter(mLogWriter);

            if (mServiceConfiguration.isUserAgentNotEmpty()) {
                epConfig.getUaConfig().setUserAgent(mServiceConfiguration.getUserAgent());
            } else {
                epConfig.getUaConfig().setUserAgent("React Native PjSip ("+ mEndpoint.libVersion().getFull() +")");
            }

            if (mServiceConfiguration.isStunServersNotEmpty()) {
                epConfig.getUaConfig().setStunServer(mServiceConfiguration.getStunServers());
            }

            epConfig.getMedConfig().setHasIoqueue(true);
            epConfig.getMedConfig().setClockRate(8000);
            epConfig.getMedConfig().setQuality(4);
            epConfig.getMedConfig().setEcOptions(1);
            epConfig.getMedConfig().setEcTailLen(200);
            epConfig.getMedConfig().setThreadCnt(2);
            mEndpoint.libInit(epConfig);

            mTrash.add(epConfig);

            // Configure transports
            {
                TransportConfig transportConfig = new TransportConfig();
                transportConfig.setQosType(pj_qos_type.PJ_QOS_TYPE_VOICE);
                mUdpTransportId = mEndpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, transportConfig);
                mTrash.add(transportConfig);
            }
            {
                TransportConfig transportConfig = new TransportConfig();
                transportConfig.setQosType(pj_qos_type.PJ_QOS_TYPE_VOICE);
                mTcpTransportId = mEndpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, transportConfig);
                mTrash.add(transportConfig);
            }
            {
                TransportConfig transportConfig = new TransportConfig();
                transportConfig.setQosType(pj_qos_type.PJ_QOS_TYPE_VOICE);
                mTlsTransportId = mEndpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, transportConfig);
                mTrash.add(transportConfig);
            }

            mEndpoint.libStart();
        } catch (Exception e) {
            Log.e(TAG, "Error while starting PJSIP", e);
        }
    }


    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        if (!mInitialized) {
            if (intent != null && intent.hasExtra("service")) {
                mServiceConfiguration = ServiceConfigurationDTO.fromMap((Map) intent.getSerializableExtra("service"));
            }

            mWorkerThread = new HandlerThread(getClass().getSimpleName(), Process.THREAD_PRIORITY_FOREGROUND);
            mWorkerThread.setPriority(Thread.MAX_PRIORITY);
            mWorkerThread.start();
            mHandler = new Handler(mWorkerThread.getLooper());
            mEmitter = new PjSipBroadcastEmiter(this);
            mAudioManager = (AudioManager) getApplicationContext().getSystemService(AUDIO_SERVICE);
            mPowerManager = (PowerManager) getApplicationContext().getSystemService(POWER_SERVICE);
            mWifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            mWifiLock = mWifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, this.getPackageName()+"-wifi-call-lock");
            mWifiLock.setReferenceCounted(false);
            // mTelephonyManager = (TelephonyManager) getApplicationContext().getSystemService(Context.TELEPHONY_SERVICE);
            // mGSMIdle = mTelephonyManager.getCallState() == TelephonyManager.CALL_STATE_IDLE;

            IntentFilter phoneStateFilter = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            registerReceiver(mPhoneStateChangedReceiver, phoneStateFilter);

            mInitialized = true;

            job(new Runnable() {
                @Override
                public void run() {
                    load();
                }
            });
        }

        if (intent != null) {
            job(new Runnable() {
                @Override
                public void run() {
                    handle(intent);
                }
            });
        }

        return START_NOT_STICKY;
    }

    private void startForegroundService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Foreground services not required before SDK 28
            return;
        }
        Log.d(TAG, "[PjSipService] startForegroundService");

        String NOTIFICATION_CHANNEL_ID = "CloudPBXService";
        String channelName = "CloudPBX Service";
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_NONE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        assert manager != null;
        manager.createNotificationChannel(chan);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        notificationBuilder.setOngoing(true)
                .setContentTitle("CloudPBX Service")
                .setPriority(NotificationManager.IMPORTANCE_MIN)
                .setCategory(Notification.CATEGORY_SERVICE);

        Activity currentActivity = PjSipModule.instance.getCurrentReactActivity();
        if (currentActivity != null) {
            Intent notificationIntent = new Intent(this, currentActivity.getClass());
            notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

            final int flag =  Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;

            PendingIntent pendingIntent = PendingIntent.getActivity(this, NOTIFICATION_ID, notificationIntent, flag);

            notificationBuilder.setContentIntent(pendingIntent);
        }

        notificationBuilder.setSmallIcon(R.drawable.ic_launcher_round);

        Log.d(TAG, "[PjSipService] Starting foreground service");

        Notification notification = notificationBuilder.build();

        try {
            startForeground(FOREGROUND_SERVICE_ID, notification);
        } catch (Exception e) {
            Log.w(TAG, "[PjSipService] Can't start foreground service : " + e.toString());
        }
    }

    private void stopForegroundService() {
        Log.d(TAG, "[PjSipService] stopForegroundService");
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception e) {
            Log.w(TAG, "[PjSipService] can't stop foreground service :" + e.toString());
        }
    }

    @Override
    public void onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            if(mWorkerThread != null) {
                mWorkerThread.quitSafely();
            }
        }

        try {
            if (mEndpoint != null) {
                mEndpoint.libDestroy();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to destroy PjSip library", e);
        }

        super.onDestroy();
    }

    private void job(Runnable job) {
        mHandler.post(job);
    }

    protected synchronized AudDevManager getAudDevManager() {
        return mEndpoint.audDevManager();
    }

    public void evict(final PjSipAccount account) {
        if (mHandler.getLooper().getThread() != Thread.currentThread()) {
            job(new Runnable() {
                @Override
                public void run() {
                    evict(account);
                }
            });
            return;
        }

        // Remove link to account
        mAccounts.remove(account);

        // Remove transport
        // try {
        //     mEndpoint.transportClose(account.getTransportId());
        // } catch (Exception e) {
        //     Log.w(TAG, "Failed to close transport for account", e);
        // }

        // Remove account in PjSip
        account.delete();

    }

    public void evict(final PjSipCall call) {
        mCalls.remove(call);
        pCalls.remove(call);
        if(call.isConference()) {
            cCalls.remove(call);
            if(cCalls.size() == 1) {
                cCalls.get(0).setConference(false);
                cCalls.clear();
            }
        }

        // int position = pCalls.indexOf(call.getId());
        // if (position != -1) {
        //     pCalls.remove(position);
        // }

        call.delete();
    }


    private void handle(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        Log.d(TAG, "Handle \""+ intent.getAction() +"\" action ("+ ArgumentUtils.dumpIntentExtraParameters(intent) +")");

        switch (intent.getAction()) {
            // General actions
            case PjActions.ACTION_START:
                handleStart(intent);
                break;

            // Account actions
            case PjActions.ACTION_CREATE_ACCOUNT:
                handleAccountCreate(intent);
                startForegroundService();
                break;
            case PjActions.ACTION_REGISTER_ACCOUNT:
                handleAccountRegister(intent);
                break;
            case PjActions.ACTION_DELETE_ACCOUNT:
                handleAccountDelete(intent);
                stopForegroundService();
                break;

            // Call actions
            case PjActions.ACTION_MAKE_CALL:
                handleCallMake(intent);
                break;
            case PjActions.ACTION_HANGUP_CALL:
                handleCallHangup(intent);
                break;
            case PjActions.ACTION_DECLINE_CALL:
                handleCallDecline(intent);
                break;
            case PjActions.ACTION_ANSWER_CALL:
                handleCallAnswer(intent);
                break;
            case PjActions.ACTION_HOLD_CALL:
                handleCallSetOnHold(intent);
                break;
            case PjActions.ACTION_UNHOLD_CALL:
                handleCallReleaseFromHold(intent);
                break;
            case PjActions.ACTION_MUTE_CALL:
                handleCallMute(intent);
                break;
            case PjActions.ACTION_UNMUTE_CALL:
                handleCallUnMute(intent);
                break;
            case PjActions.ACTION_USE_SPEAKER_CALL:
                handleCallUseSpeaker(intent);
                break;
            case PjActions.ACTION_USE_EARPIECE_CALL:
                handleCallUseEarpiece(intent);
                break;
            case PjActions.ACTION_XFER_CALL:
                handleCallXFer(intent);
                break;
            case PjActions.ACTION_XFER_REPLACES_CALL:
                handleCallXFerReplaces(intent);
                break;
            case PjActions.ACTION_REDIRECT_CALL:
                handleCallRedirect(intent);
                break;
            case PjActions.ACTION_DTMF_CALL:
                handleCallDtmf(intent);
                break;
            case PjActions.ACTION_CHANGE_CODEC_SETTINGS:
                handleChangeCodecSettings(intent);
                break;
            case PjActions.ACTION_START_CONFERENCE_CALL:
                handleStartConferenceCall(intent);
                break;
            case PjActions.ACTION_STOP_CONFERENCE_CALL:
                handleStopConferenceCall(intent);
                break;
            case PjActions.ACTION_SPLIT_FROM_CONFERENCE_CALL:
                handleSplitFromConferenceCall(intent);
                break;

            // Configuration actions
            case PjActions.ACTION_SET_SERVICE_CONFIGURATION:
                handleSetServiceConfiguration(intent);
                break;
        }
    }

    private void handleStart(Intent intent) {
        try {
            // Modify existing configuration if it changes during application reload.
            if (intent.hasExtra("service")) {
                ServiceConfigurationDTO newServiceConfiguration = ServiceConfigurationDTO.fromMap((Map) intent.getSerializableExtra("service"));
                if (!newServiceConfiguration.equals(mServiceConfiguration)) {
                    updateServiceConfiguration(newServiceConfiguration);
                }
            }

            CodecInfoVector2 codVect = mEndpoint.codecEnum2();
            JSONObject codecs = new JSONObject();

            for(int i=0;i<codVect.size();i++){
                CodecInfo codInfo = codVect.get(i);
                String codId = codInfo.getCodecId();
                short priority = codInfo.getPriority();
                codecs.put(codId, priority);
                codInfo.delete();
            }

            JSONObject settings = mServiceConfiguration.toJson();
            settings.put("codecs", codecs);

            mEmitter.fireStarted(intent, mAccounts, mCalls, settings);
        } catch (Exception error) {
            Log.e(TAG, "Error while building codecs list", error);
            throw new RuntimeException(error);
        }
    }

    private void handleSetServiceConfiguration(Intent intent) {
        try {
            updateServiceConfiguration(ServiceConfigurationDTO.fromIntent(intent));

            // Emmit response
            mEmitter.fireIntentHandled(intent, mServiceConfiguration.toJson());
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void updateServiceConfiguration(ServiceConfigurationDTO configuration) {
        mServiceConfiguration = configuration;
    }

    private void handleAccountCreate(Intent intent) {
        try {
            AccountConfigurationDTO accountConfiguration = AccountConfigurationDTO.fromIntent(intent);
            PjSipAccount account = doAccountCreate(accountConfiguration);

            // Emmit response
            mEmitter.fireAccountCreated(intent, account);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleAccountRegister(Intent intent) {
        try {
            int accountId = intent.getIntExtra("account_id", -1);
            boolean renew = intent.getBooleanExtra("renew", false);
            PjSipAccount account = null;

            for (PjSipAccount a : mAccounts) {
                if (a.getId() == accountId) {
                    account = a;
                    break;
                }
            }

            if (account == null) {
                throw new Exception("Account with \""+ accountId +"\" id not found");
            }

            account.register(renew);

            // -----
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private PjSipAccount doAccountCreate(AccountConfigurationDTO configuration) throws Exception {
        AccountConfig cfg = new AccountConfig();

        // General settings
        AuthCredInfo cred = new AuthCredInfo(
            "Digest",
            configuration.getNomalizedRegServer(),
            configuration.getUsername(),
            0,
            configuration.getPassword()
        );

        String idUri = configuration.getIdUri();
        String regUri = configuration.getRegUri();

        cfg.setIdUri(idUri);
        cfg.getRegConfig().setRegistrarUri(regUri);
        cfg.getRegConfig().setRegisterOnAdd(configuration.isRegOnAdd());
        cfg.getSipConfig().getAuthCreds().add(cred);
        cfg.getRegConfig().setTimeoutSec(configuration.getRegTimeout());
        cfg.getRegConfig().setRetryIntervalSec(configuration.getRetryIntervalSec());
        cfg.getVideoConfig().getRateControlBandwidth();
        cfg.getRegConfig().setDropCallsOnFail(true);

        // Registration settings

        if (configuration.getContactParams() != null) {
            cfg.getSipConfig().setContactParams(configuration.getContactParams());
        }
        if (configuration.getContactUriParams() != null) {
            cfg.getSipConfig().setContactUriParams(configuration.getContactUriParams());
        }
        if (configuration.getRegContactParams() != null) {
            Log.w(TAG, "Property regContactParams are not supported on android, use contactParams instead");
        }

        if (configuration.getRegHeaders() != null && configuration.getRegHeaders().size() > 0) {
            SipHeaderVector headers = new SipHeaderVector();

            for (Map.Entry<String, String> entry : configuration.getRegHeaders().entrySet()) {
                SipHeader hdr = new SipHeader();
                hdr.setHName(entry.getKey());
                hdr.setHValue(entry.getValue());
                headers.add(hdr);
            }

            cfg.getRegConfig().setHeaders(headers);
        }

        // Transport settings
        int transportId = mTcpTransportId;

        if (configuration.isTransportNotEmpty()) {
            switch (configuration.getTransport()) {
                case "UDP":
                    transportId = mUdpTransportId;
                    break;
                case "TLS":
                    transportId = mTlsTransportId;
                    break;
                default:
                    Log.w(TAG, "Illegal \""+ configuration.getTransport() +"\" transport (possible values are UDP, TCP or TLS) use TCP instead");
                    break;
            }
        }

        cfg.getSipConfig().setTransportId(transportId);

        if (configuration.isProxyNotEmpty()) {
            StringVector v = new StringVector();
            v.add(configuration.getProxy());
            cfg.getSipConfig().setProxies(v);
        }

        if (configuration.getEnableSRTP()) {
            // Enable SRTP
            Log.w(TAG, "NUACOM-MSG: Enabling SRTP as mandatory");
            cfg.getMediaConfig().setSrtpUse(pjmedia_srtp_use.PJMEDIA_SRTP_MANDATORY);
            cfg.getMediaConfig().setSrtpSecureSignaling(0);
        }

        cfg.getMediaConfig().getTransportConfig().setQosType(pj_qos_type.PJ_QOS_TYPE_VOICE);

        cfg.getVideoConfig().setAutoShowIncoming(true);
        cfg.getVideoConfig().setAutoTransmitOutgoing(true);

        int cap_dev = cfg.getVideoConfig().getDefaultCaptureDevice();
        mEndpoint.vidDevManager().setCaptureOrient(cap_dev, pjmedia_orient.PJMEDIA_ORIENT_ROTATE_270DEG, true);

        // -----

        PjSipAccount account = new PjSipAccount(this, transportId, configuration);
        account.create(cfg);

        mTrash.add(cfg);
        mTrash.add(cred);

        mAccounts.add(account);

        return account;
    }

    private void handleAccountDelete(Intent intent) {
        try {
            int accountId = intent.getIntExtra("account_id", -1);
            PjSipAccount account = null;

            for (PjSipAccount a : mAccounts) {
                if (a.getId() == accountId) {
                    account = a;
                    break;
                }
            }

            if (account == null) {
                throw new Exception("Account with \""+ accountId +"\" id not found");
            }

            evict(account);

            // -----
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallMake(Intent intent) {
        try {
            int accountId = intent.getIntExtra("account_id", -1);
            PjSipAccount account = findAccount(accountId);
            String destination = intent.getStringExtra("destination");
            String settingsJson = intent.getStringExtra("settings");
            String messageJson = intent.getStringExtra("message");

            // -----
            CallOpParam callOpParam = new CallOpParam(true);

            if (settingsJson != null) {
                CallSettingsDTO settingsDTO = CallSettingsDTO.fromJson(settingsJson);
                CallSetting callSettings = new CallSetting();

                if (settingsDTO.getAudioCount() != null) {
                    callSettings.setAudioCount(settingsDTO.getAudioCount());
                }
                if (settingsDTO.getVideoCount() != null) {
                    callSettings.setVideoCount(settingsDTO.getVideoCount());
                }
                if (settingsDTO.getFlag() != null) {
                    callSettings.setFlag(settingsDTO.getFlag());
                }
                if (settingsDTO.getRequestKeyframeMethod() != null) {
                    callSettings.setReqKeyframeMethod(settingsDTO.getRequestKeyframeMethod());
                }

                callOpParam.setOpt(callSettings);

                mTrash.add(callSettings);
            }

            if (messageJson != null) {
                SipMessageDTO messageDTO = SipMessageDTO.fromJson(messageJson);
                SipTxOption callTxOption = new SipTxOption();

                if (messageDTO.getTargetUri() != null) {
                    callTxOption.setTargetUri(messageDTO.getTargetUri());
                }
                if (messageDTO.getContentType() != null) {
                    callTxOption.setContentType(messageDTO.getContentType());
                }
                if (messageDTO.getHeaders() != null) {
                    callTxOption.setHeaders(PjSipUtils.mapToSipHeaderVector(messageDTO.getHeaders()));
                }
                if (messageDTO.getBody() != null) {
                    callTxOption.setMsgBody(messageDTO.getBody());
                }

                callOpParam.setTxOption(callTxOption);

                mTrash.add(callTxOption);
            }

            PjSipCall call = new PjSipCall(account);
            call.makeCall(destination, callOpParam);

            callOpParam.delete();

            // Automatically put other calls on hold.
            doPauseParallelCalls(call);

            mCalls.add(call);
            pCalls.add(call);
            mEmitter.fireIntentHandled(intent, call.toJson());
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallHangup(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            PjSipCall call = findCall(callId);
            final int callState = call.getInfo().getState();
            call.hangup(new CallOpParam(true));

            mEmitter.fireIntentHandled(intent);
            if (callState != pjsip_inv_state.PJSIP_INV_STATE_EARLY) {
//                emmitCallTerminated(call, new OnCallStateParam());
            }
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallDecline(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);

            // -----
            PjSipCall call = findCall(callId);
            CallOpParam prm = new CallOpParam(true);
            prm.setStatusCode(pjsip_status_code.PJSIP_SC_DECLINE);
            call.hangup(prm);
            prm.delete();

            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallAnswer(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);

            // -----
            PjSipCall call = findCall(callId);
            CallOpParam prm = new CallOpParam();
            prm.setStatusCode(pjsip_status_code.PJSIP_SC_OK);
            call.answer(prm);

            // Automatically put other calls on hold.
            doPauseParallelCalls(call);

            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallSetOnHold(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);

            // -----
            PjSipCall call = findCall(callId);
            call.hold();

            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallReleaseFromHold(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);

            // -----
            PjSipCall call = findCall(callId);
            call.unhold();

            // Automatically put other calls on hold.
            if(!call.isConference()) {
                doPauseParallelCalls(call);
            }

            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallMute(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);

            // -----
            PjSipCall call = findCall(callId);
            call.mute();

            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallUnMute(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);

            // -----
            PjSipCall call = findCall(callId);
            call.unmute();

            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallUseSpeaker(Intent intent) {
        try {
            mAudioManager.setSpeakerphoneOn(true);
            mUseSpeaker = true;

            for (PjSipCall call : mCalls) {
                emmitCallUpdated(call);
            }

            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallUseEarpiece(Intent intent) {
        try {
            mAudioManager.setSpeakerphoneOn(false);
            mUseSpeaker = false;

            for (PjSipCall call : mCalls) {
                emmitCallUpdated(call);
            }

            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallXFer(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            String destination = intent.getStringExtra("destination");

            // -----
            PjSipCall call = findCall(callId);
            call.xfer(destination, new CallOpParam(true));

            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallXFerReplaces(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            int destinationCallId = intent.getIntExtra("dest_call_id", -1);

            // -----
            PjSipCall call = findCall(callId);
            PjSipCall destinationCall = findCall(destinationCallId);
            call.xferReplaces(destinationCall, new CallOpParam(true));

            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallRedirect(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            String destination = intent.getStringExtra("destination");

            // -----
            PjSipCall call = findCall(callId);
            call.redirect(destination);

            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleCallDtmf(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            String digits = intent.getStringExtra("digits");

            // -----
            PjSipCall call = findCall(callId);
            call.dialDtmf(digits);

            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleChangeCodecSettings(Intent intent) {
        try {
            Bundle codecSettings = intent.getExtras();

            // -----
            if (codecSettings != null) {
                for (String key : codecSettings.keySet()) {

                    if (!key.equals("callback_id")) {

                        short priority = (short) codecSettings.getInt(key);

                        mEndpoint.codecSetPriority(key, priority);

                    }

                }
            }

            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    List<PjSipCall> getConferenceCalls() {
        return cCalls;
    }
    private void handleStartConferenceCall(Intent intent) {
        try {
            // set conference flag to true for all calls
            for (PjSipCall call : mCalls) {
                call.setConference(true);
                cCalls.add(call);
            }

            // unhold all calls
            for (PjSipCall call : mCalls) {
                if (call.isHeld()) {
                    call.unhold();
                }
            }

            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleStopConferenceCall(Intent intent) {
        try {
            for (PjSipCall call : cCalls) {
                call.setConference(false);
            }

            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private void handleSplitFromConferenceCall(Intent intent) {
        try {
            int callId = intent.getIntExtra("call_id", -1);
            PjSipCall call = findCall(callId);
            call.setConference(false);
            cCalls.remove(call);
            if(cCalls.size() == 1) {
                cCalls.get(0).setConference(false);
                cCalls.clear();
            }
            mEmitter.fireIntentHandled(intent);
        } catch (Exception e) {
            mEmitter.fireIntentHandled(intent, e);
        }
    }

    private PjSipAccount findAccount(int id) throws Exception {
        for (PjSipAccount account : mAccounts) {
            if (account.getId() == id) {
                return account;
            }
        }

        throw new Exception("Account with specified \""+ id +"\" id not found");
    }

    private PjSipCall findCall(int id) throws Exception {
        for (PjSipCall call : mCalls) {
            if (call.getId() == id) {
                return call;
            }
        }

        throw new Exception("Call with specified \""+ id +"\" id not found");
    }

    void emmitRegistrationStarted(PjSipAccount account, OnRegStartedParam prm) {
//        getEmitter().fireRegistrationChangeEvent(account);
    }

    void emmitRegistrationChanged(PjSipAccount account, OnRegStateParam prm) {
        getEmitter().fireRegistrationChangeEvent(account);
    }

    void emmitMessageReceived(PjSipAccount account, PjSipMessage message) {
        getEmitter().fireMessageReceivedEvent(message);
    }

    void emmitCallReceived(PjSipAccount account, PjSipCall call) {
        try {
            // // Automatically decline incoming call when user uses GSM
            // if (!mGSMIdle) {
            //     try {
            //         call.hangup(new CallOpParam(true));
            //     } catch (Exception e) {
            //         Log.w(TAG, "Failed to decline incoming call when user uses GSM", e);
            //     }

            //     return;
            // }

            /**
            // Automatically start application when incoming call received.
            if (mAppHidden) {
                try {
                    String ns = getApplicationContext().getPackageName();
                    String cls = ns + ".MainActivity";

                    Intent intent = new Intent(getApplicationContext(), Class.forName(cls));
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.EXTRA_DOCK_STATE_CAR);
                    intent.addCategory(Intent.CATEGORY_LAUNCHER);
                    intent.putExtra("foreground", true);

                    startActivity(intent);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to open application on received call", e);
                }
            }

            job(new Runnable() {
                @Override
                public void run() {
                    // Brighten screen at least 10 seconds
                    PowerManager.WakeLock wl = mPowerManager.newWakeLock(
                        PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE | PowerManager.FULL_WAKE_LOCK,
                        "incoming_call"
                    );
                    wl.acquire(10000);

                    if (mCalls.size() == 0) {
                        mAudioManager.setSpeakerphoneOn(true);
                    }
                }
            });
            **/

            mCalls.add(call);
            mEmitter.fireCallReceivedEvent(call);

            // Send 180 ringing sip status code
            CallOpParam prm = new CallOpParam();
            prm.setStatusCode(pjsip_status_code.PJSIP_SC_RINGING);
            call.answer(prm);
        } catch (Exception e) {
            Log.w(TAG, "Failed to handle call received event", e);
        }
    }

    void emmitCallStateChanged(PjSipCall call, OnCallStateParam prm) {
        try {
            if (call.getInfo().getState() == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                emmitCallTerminated(call, prm);
            } else {
                emmitCallChanged(call, prm);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to handle call state event", e);
        }
    }

    void emmitCallChanged(PjSipCall call, OnCallStateParam prm) {
        try {
            final int callId = call.getId();
            final int callState = call.getInfo().getState();

            job(new Runnable() {
                @Override
                public void run() {
                    // Acquire wake lock
                    if (mIncallWakeLock == null) {
                        mIncallWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PjSipService:incall");
                    }
                    if (!mIncallWakeLock.isHeld()) {
                        mIncallWakeLock.acquire();
                    }

                    // Ensure that ringing sound is stopped
                    if (callState != pjsip_inv_state.PJSIP_INV_STATE_INCOMING && !mUseSpeaker && mAudioManager.isSpeakerphoneOn()) {
                        mAudioManager.setSpeakerphoneOn(false);
                    }

                    // Acquire wifi lock
                    mWifiLock.acquire();

                    if (callState == pjsip_inv_state.PJSIP_INV_STATE_EARLY || callState == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED) {
                        mAudioManager.setMode(AudioManager.MODE_IN_CALL);
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Failed to retrieve call state", e);
        }

        mEmitter.fireCallChanged(call);
    }

    void emmitCallTerminated(PjSipCall call, OnCallStateParam prm) {
        job(new Runnable() {
            @Override
            public void run() {
                // Release wake lock
                if (mCalls.size() == 1) {
                    if (mIncallWakeLock != null && mIncallWakeLock.isHeld()) {
                        mIncallWakeLock.release();
                    }
                }

                // Release wifi lock
                if (mCalls.size() == 1) {
                    mWifiLock.release();
                }

                // Reset audio settings
                if (mCalls.size() == 1) {
                    mAudioManager.setSpeakerphoneOn(false);
                    mAudioManager.setMode(AudioManager.MODE_NORMAL);
                }
            }
        });

        mEmitter.fireCallTerminated(call);
        evict(call);
    }

    void emmitCallUpdated(PjSipCall call) {
        mEmitter.fireCallChanged(call);
    }

    /**
     * Pauses active calls once user answer to incoming calls.
     */
    private void doPauseParallelCalls(PjSipCall activeCall) {
        for (PjSipCall call : mCalls) {
            if (activeCall.getId() == call.getId()) {
                continue;
            }

            try {
                call.hold();
            } catch (Exception e) {
                Log.w(TAG, "Failed to put call on hold", e);
            }
        }
    }

    /**
     * Pauses all calls, used when received GSM call.
     */
    private void doPauseAllCalls() {
        for (PjSipCall call : mCalls) {
            try {
                if (pCalls.contains(call)) {
                    call.hold();
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to put call on hold", e);
            }
        }
    }

    protected class PhoneStateChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String extraState = intent.getStringExtra(TelephonyManager.EXTRA_STATE);

            if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(extraState)) {
                Log.d(TAG, "NUACOM-MSG: GSM call received, pause all SIP calls.");

                job(new Runnable() {
                    @Override
                    public void run() {
                        doPauseAllCalls();
                    }
                });
            }
        }
    }
}
