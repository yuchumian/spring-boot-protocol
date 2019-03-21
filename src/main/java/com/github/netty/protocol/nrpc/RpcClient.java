package com.github.netty.protocol.nrpc;

import com.github.netty.annotation.Protocol;
import com.github.netty.core.AbstractChannelHandler;
import com.github.netty.core.AbstractNettyClient;
import com.github.netty.core.util.AnnotationMethodToParameterNamesFunction;
import com.github.netty.core.util.ReflectUtil;
import com.github.netty.core.util.StringUtil;
import com.github.netty.protocol.nrpc.exception.RpcConnectException;
import com.github.netty.protocol.nrpc.exception.RpcException;
import com.github.netty.protocol.nrpc.service.RpcCommandService;
import com.github.netty.protocol.nrpc.service.RpcDBService;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.github.netty.protocol.nrpc.RpcPacket.ACK_YES;

/**
 * RPC client
 * @author wangzihao
 *  2018/8/18/018
 */
public class RpcClient extends AbstractNettyClient{
    /**
     * Request a lock map
     */
    protected static final AttributeKey<Map<Integer,RpcFuture>> FUTURE_MAP_ATTR = AttributeKey.valueOf(Map.class+"#futureMap");
    /**
     * Generate request id
     */
    protected static final AttributeKey<AtomicInteger> REQUEST_ID_INCR_ATTR = AttributeKey.valueOf(AtomicInteger.class+"#AtomicInteger");

    private RpcEncoder rpcEncoder = new RpcEncoder();
    private final Map<String,RpcClientInstance> rpcInstanceMap = new WeakHashMap<>();
    private int idleTime = 10;
    private RpcCommandService rpcCommandService;
    private RpcDBService rpcDBService;
    /**
     * Connection status
     */
    private State state;
    /**
     * The thread that creates the client
     */
    private Thread thread;
    /**
     * Automatic reconnect Future
     */
    private ScheduledFuture<?> autoReconnectScheduledFuture;

    private DataCodec dataCodec;

    public RpcClient(String remoteHost, int remotePort) {
        this(new InetSocketAddress(remoteHost, remotePort));
    }

    public RpcClient(InetSocketAddress address) {
        this("",address);
    }

    public RpcClient(String namePre, InetSocketAddress remoteAddress) {
        super(namePre + Thread.currentThread().getName()+"-", remoteAddress);
        this.thread = Thread.currentThread();
        this.dataCodec = new JsonDataCodec();
    }

    public void setIdleTime(int idleTime) {
        this.idleTime = idleTime;
    }

    /**
     * Enable automatic reconnection
     */
    public void enableAutoReconnect(){
        enableAutoReconnect(20,TimeUnit.SECONDS,null,true);
    }

    /**
     * Enable automatic reconnection
     * @param heartIntervalSecond The interval at which heartbeat tasks are placed on the queue
     * @param timeUnit Unit of time
     * @param reconnectSuccessHandler Callback method after successful reconnect
     * @param isLogHeartEvent Whether heartbeat event logging is enabled
     */
    public void enableAutoReconnect(int heartIntervalSecond, TimeUnit timeUnit, Consumer<RpcClient> reconnectSuccessHandler, boolean isLogHeartEvent){
        autoReconnectScheduledFuture = RpcClientHeartbeatTask.schedule(heartIntervalSecond,timeUnit,reconnectSuccessHandler,this,isLogHeartEvent);
    }

    /**
     * New implementation class
     * @param clazz  interface
     * @param <T> type
     * @return  Interface implementation class
     */
    public <T>T newInstance(Class<T> clazz){
        int timeout = Protocol.RpcService.DEFAULT_TIME_OUT;
        String serviceName = "";

        Protocol.RpcService rpcInterfaceAnn = ReflectUtil.findAnnotation(clazz, Protocol.RpcService.class);
        if (rpcInterfaceAnn != null) {
            timeout = rpcInterfaceAnn.timeout();
            serviceName = rpcInterfaceAnn.value();
        }
        if(serviceName.isEmpty()){
            serviceName = "/"+StringUtil.firstLowerCase(clazz.getSimpleName());
        }
        return newInstance(clazz,timeout,serviceName);
    }

    /**
     * New implementation class
     * @param clazz  interface
     * @param timeout timeout
     * @param serviceName serviceName
     * @param <T> type
     * @return Interface implementation class
     */
    public <T>T newInstance(Class<T> clazz,int timeout,String serviceName){
        return newInstance(clazz,timeout,serviceName, new AnnotationMethodToParameterNamesFunction(Arrays.asList(Protocol.RpcParam.class)));
    }

    /**
     * New implementation class
     * @param clazz  interface
     * @param timeout timeout
     * @param serviceName serviceName
     * @param methodToParameterNamesFunction Method to a function with a parameter name
     * @param <T> type
     * @return Interface implementation class
     */
    public <T>T newInstance(Class<T> clazz, int timeout, String serviceName, Function<Method,String[]> methodToParameterNamesFunction){
        RpcClientInstance rpcInstance = newRpcInstance(clazz,timeout,serviceName,methodToParameterNamesFunction);
        Object instance = Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz}, rpcInstance);
        return (T) instance;
    }

    /**
     * New implementation class
     * @param clazz  interface
     * @param timeout timeout
     * @param serviceName serviceName
     * @param  methodToParameterNamesFunction Method to a function with a parameter name
     * @return Interface implementation class
     */
    public RpcClientInstance newRpcInstance(Class clazz, int timeout, String serviceName, Function<Method,String[]> methodToParameterNamesFunction){
        RpcClientInstance rpcInstance = new RpcClientInstance(timeout, serviceName,this::getSocketChannel,
                dataCodec,clazz,methodToParameterNamesFunction);
        rpcInstanceMap.put(serviceName,rpcInstance);
        return rpcInstance;
    }

    /**
     * Gets the implementation class
     * @param serviceName serviceName
     * @return RpcClientInstance
     */
    public RpcClientInstance getRpcInstance(String serviceName) {
        return rpcInstanceMap.get(serviceName);
    }

    /**
     * New initialize all
     * @return ChannelInitializer
     */
    @Override
    protected ChannelInitializer<? extends Channel> newInitializerChannelHandler() {
        return new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) throws Exception {
                ChannelPipeline pipeline = channel.pipeline();

                pipeline.addLast("idleStateHandler", new IdleStateHandler(idleTime, 0, 0));
                pipeline.addLast(rpcEncoder);
                pipeline.addLast(new RpcDecoder());
                pipeline.addLast(new RpcClientChannelHandler());
            }
        };
    }

    @Override
    public SocketChannel getSocketChannel() {
        SocketChannel socketChannel = super.getSocketChannel();
        if(socketChannel == null){
            throw new RpcConnectException("The ["+getRemoteAddress()+"] channel no connect");
        }
        if(!socketChannel.isActive()){
            throw new RpcConnectException("The ["+getRemoteAddress()+"] channel no active");
        }
        if(!socketChannel.isWritable()){
            throw new RpcConnectException("The ["+getRemoteAddress()+"] channel no writable");
        }
        return socketChannel;
    }

    @Override
    public boolean isConnect() {
        if(rpcCommandService == null){
            return super.isConnect();
        }

        SocketChannel channel = super.getSocketChannel();
        if(channel == null || !channel.isActive()){
            return false;
        }
        try {
            return rpcCommandService.ping() != null;
        }catch (RpcException e){
            return false;
        }
    }

    @Override
    public boolean connect() {
        boolean success = super.connect();
        if(success){
            state = State.UP;
        }else {
            state = State.DOWN;
        }
        return success;
    }

    @Override
    public void stop() {
        super.stop();
        if(autoReconnectScheduledFuture != null){
            autoReconnectScheduledFuture.cancel(false);
        }
    }

    @Override
    protected void startAfter() {
    }

    @Override
    protected void stopAfter(Throwable cause) {
        if(cause != null){
            logger.error(cause.getMessage(),cause);
        }
    }

    /**
     * Access to data service
     * @return RpcDBService
     */
    public RpcDBService getRpcDBService() {
        if(rpcDBService == null){
            synchronized (this) {
                if(rpcDBService == null) {
                    rpcDBService = newInstance(RpcDBService.class);
                }
            }
        }
        return rpcDBService;
    }

    /**
     * Get command service
     * @return RpcCommandService
     */
    public RpcCommandService getRpcCommandService() {
        if(rpcCommandService == null){
            synchronized (this) {
                if(rpcCommandService == null) {
                    rpcCommandService = newInstance(RpcCommandService.class);
                }
            }
        }
        return rpcCommandService;
    }

    /**
     * Get connection status
     * @return State
     */
    public State getState() {
        return state;
    }

    /**
     * Gets the thread that created the client
     * @return Thread
     */
    public Thread getThread() {
        return thread;
    }

    /**
     * Client connection status
     */
    public enum State{
        DOWN,
        UP
    }

    @Override
    public String toString() {
        return super.toString()+"{" +
                "state=" + state +
                '}';
    }


    public static class RpcClientChannelHandler extends AbstractChannelHandler<RpcPacket.ResponsePacket,Object> {
        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            //For 4.0.x version to 4.1.x version adaptation
            ctx.channel().attr(FUTURE_MAP_ATTR).set(intObjectHashMapConstructor != null?
                    (Map)intObjectHashMapConstructor.newInstance(64) : new HashMap<>(64));
            ctx.channel().attr(REQUEST_ID_INCR_ATTR).set(new AtomicInteger());
        }

        @Override
        protected void onMessageReceived(ChannelHandlerContext ctx, RpcPacket.ResponsePacket rpcResponse) throws Exception {
            Map<Integer,RpcFuture> futureMap = ctx.channel().attr(FUTURE_MAP_ATTR).get();

            RpcFuture future = futureMap.remove(rpcResponse.getRequestId());
            //If the fetch does not indicate that the timeout has occurred, it is released
            if (future == null) {
                return;
            }
            future.done(rpcResponse);
        }

        @Override
        protected void onReaderIdle(ChannelHandlerContext ctx) {
            //heart beat
            RpcPacket packet = new RpcPacket(RpcPacket.PING_TYPE);
            packet.setAck(ACK_YES);
            ctx.writeAndFlush(packet).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        }

        private static Constructor<?> intObjectHashMapConstructor;
        static {
            try {
                intObjectHashMapConstructor = Class.forName("io.netty.util.collection.IntObjectHashMap").getConstructor(int.class);;
            } catch (Exception e) {
                intObjectHashMapConstructor = null;
            }
        }
    }

}
