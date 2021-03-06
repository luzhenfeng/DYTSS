package com.nhsoft.base.webSocket;


import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.text.TextUtils;

import com.lzf.http.global.SPKeyGlobal;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import priv.lzf.mvvmhabit.base.BaseApplication;
import priv.lzf.mvvmhabit.utils.KLog;
import priv.lzf.mvvmhabit.utils.SPUtils;

public class WsManager {
    private static WsManager mInstance;
    private final String TAG = this.getClass().getSimpleName();

    /**
     * WebSocket config
     */
    private static final int FRAME_QUEUE_SIZE = 5;
    private static final int CONNECT_TIMEOUT = 5000;
//    private static final String DEF_TEST_URL = "ws://116.62.178.42:3134?token="+SharePreferencesUtils.getInstance().getString(ConstantPreference.TOKEN);//测试服默认地址
//    private static final String DEF_RELEASE_URL = "ws://116.62.178.42:3134?token=TOKEN"+SharePreferencesUtils.getInstance().getString(ConstantPreference.TOKEN);//正式服默认地址
    private static final String DEF_URL = "ws://47.110.138.111:8089/"+ SPUtils.getInstance().getString(SPKeyGlobal.ACCESS_TOKEN,null);
    private String url;

    private WsStatus mStatus;
    private WebSocket ws;
    private WsListener mListener;
    private boolean isFirst=true;
    private boolean disconnect;

    private Handler handler=new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            sendText();
            Message messenger=handler.obtainMessage();
            messenger.what=1;
            handler.sendMessageDelayed(messenger,5000);
            return false;
        }
    });

    private WsManager() {
    }

    public static WsManager getInstance(){
        if(mInstance == null){
            synchronized (WsManager.class){
                if(mInstance == null){
                    mInstance = new WsManager();
                }
            }
        }
        return mInstance;
    }

    public void init(){
        try {
            /**
             * configUrl其实是缓存在本地的连接地址
             * 这个缓存本地连接地址是app启动的时候通过http请求去服务端获取的,
             * 每次app启动的时候会拿当前时间与缓存时间比较,超过6小时就再次去服务端获取新的连接地址更新本地缓存
             */
            String configUrl = "";
            url = DEF_URL;
            ws = new WebSocketFactory().createSocket(url, CONNECT_TIMEOUT)
                    .setFrameQueueSize(FRAME_QUEUE_SIZE)//设置帧队列最大值为5
                    .setMissingCloseFrameAllowed(false)//设置不允许服务端关闭连接却未发送关闭帧
                    .addListener(mListener = new WsListener())//添加回调监听
                    .connectAsynchronously();//异步连接
            setStatus(WsStatus.CONNECTING);
            KLog.e("第一次连接");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    /**
     * 继承默认的监听空实现WebSocketAdapter,重写我们需要的方法
     * onTextMessage 收到文字信息
     * onConnected 连接成功
     * onConnectError 连接失败
     * onDisconnected 连接关闭
     */
    class WsListener extends WebSocketAdapter {
        @Override
        public void onTextMessage(WebSocket websocket, String text) throws Exception {
            super.onTextMessage(websocket, text);
            KLog.d(text);
//            handler.sendEmptyMessageDelayed(MESSAGE_LOGIN,5000);
            if (isFirst){
                Message messenger=handler.obtainMessage();
                messenger.what=1;
                handler.sendMessageDelayed(messenger,5000);
                isFirst=false;
            }
        }


        @Override
        public void onConnected(WebSocket websocket, Map<String, List<String>> headers)
                throws Exception {
            super.onConnected(websocket, headers);
            KLog.d("连接成功");
            setStatus(WsStatus.CONNECT_SUCCESS);
            cancelReconnect();//连接成功的时候取消重连,初始化连接次数
            ws.sendText("{\"command\":\"subscribe\",\"identifier\":\"{\\\"channel\\\":\\\"PosChannel\\\"}\"}");
        }


        @Override
        public void onConnectError(WebSocket websocket, WebSocketException exception)
                throws Exception {
            super.onConnectError(websocket, exception);
            KLog.d("连接错误");
            handler.removeMessages(1);
            setStatus(WsStatus.CONNECT_FAIL);
            reconnect();//连接错误的时候调用重连方法
        }


        @Override
        public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer)
                throws Exception {
            super.onDisconnected(websocket, serverCloseFrame, clientCloseFrame, closedByServer);
            KLog.d("断开连接");
            handler.removeMessages(1);
            setStatus(WsStatus.CONNECT_FAIL);
            reconnect();//连接断开的时候调用重连方法
        }
    }

    private void setStatus(WsStatus status){
        this.mStatus = status;
    }

    private WsStatus getStatus(){
        return mStatus;
    }

    public void disconnect(){
        if(ws != null){
            ws.disconnect();
        }
        disconnect=true;
    }


    private Handler mHandler = new Handler();

    private int reconnectCount = 0;//重连次数
    private long minInterval = 3000;//重连最小时间间隔
    private long maxInterval = 60000;//重连最大时间间隔


    public void reconnect() {
        if (disconnect){
            return;
        }
        isFirst=true;
        if (!isNetConnect()) {
            reconnectCount = 0;
            KLog.e("重连失败网络不可用");
            return;
        }

        //这里其实应该还有个用户是否登录了的判断 因为当连接成功后我们需要发送用户信息到服务端进行校验
        //由于我们这里是个demo所以省略了
        if (ws != null &&
                !ws.isOpen() &&//当前连接断开了
                getStatus() != WsStatus.CONNECTING) {//不是正在重连状态

            reconnectCount++;
            setStatus(WsStatus.CONNECTING);

            long reconnectTime = minInterval;
            if (reconnectCount > 3) {
                url = DEF_URL;
                long temp = minInterval * (reconnectCount - 2);
                reconnectTime = temp > maxInterval ? maxInterval : temp;
            }

            KLog.e("准备开始第"+reconnectCount+"次重连,重连间隔"+reconnectTime+" -- url:"+ url);
            mHandler.postDelayed(mReconnectTask, reconnectTime);
        }
    }

    public void sendText(){
        ws.sendText("ping");
    }


    private Runnable mReconnectTask = new Runnable() {

        @Override
        public void run() {
            try {
                ws = new WebSocketFactory().createSocket(url, CONNECT_TIMEOUT)
                        .setFrameQueueSize(FRAME_QUEUE_SIZE)//设置帧队列最大值为5
                        .setMissingCloseFrameAllowed(false)//设置不允许服务端关闭连接却未发送关闭帧
                        .addListener(mListener = new WsListener())//添加回调监听
                        .connectAsynchronously();//异步连接
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    };


    private void cancelReconnect() {
        reconnectCount = 0;
        mHandler.removeCallbacks(mReconnectTask);
    }


    private boolean isNetConnect() {
        ConnectivityManager connectivity = (ConnectivityManager) BaseApplication.getInstance()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity != null) {
            NetworkInfo info = connectivity.getActiveNetworkInfo();
            if (info != null && info.isConnected()) {
                // 当前网络是连接的
                if (info.getState() == NetworkInfo.State.CONNECTED) {
                    // 当前所连接的网络可用
                    return true;
                }
            }
        }
        return false;
    }

}

