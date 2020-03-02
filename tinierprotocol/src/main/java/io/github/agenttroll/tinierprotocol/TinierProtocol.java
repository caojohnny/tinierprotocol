package io.github.agenttroll.tinierprotocol;

import com.google.common.collect.MapMaker;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TinierProtocol - Bukkit-ONLY re-work of TinyProtocol,
 * originally authored by Comphenix.
 * <p>
 * This class is not
 * meant to be a drop-in replacement, but it was
 * nevertheless heavily inspired by TinyProtocol
 * nonetheless.
 * <p>
 * You can find the source for that class here:
 * https://github.com/aadnk/ProtocolLib/blob/master/modules/TinyProtocol/src/main/java/com/comphenix/tinyprotocol/TinyProtocol.java
 * <p>
 * This class provides reflective packet interception of
 * both clientbound and serverbound packets, as well as
 * login and ping packets. Support is also provided for
 * sending packets.
 * <p>
 * Caveats:
 * - This is not intended to be for production use. This is
 * a proof-of-concept ONLY. Use at your own risk
 * - I have not thoroughly investigated the performance hit
 * taken from the use of reflective Proxies, but I suspect
 * that it is non-trivial
 */
public class TinierProtocol {
    private static final String OBC_PACKAGE_VER =
            Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
    private static final String OBC_PACKAGE = "org.bukkit.craftbukkit." + OBC_PACKAGE_VER + ".";
    private static final String NMS_PACKAGE = "net.minecraft.server." + OBC_PACKAGE_VER + ".";
    private static final String NETTY_PACKAGE = "io.netty.channel.";

    // obc.CraftServer
    private static final Class<?> CS_CLS = lookupClass(OBC_PACKAGE + "CraftServer");
    // DedicatedPlayerList CraftServer#getHandle()
    private static final Method CS_GET_HANDLE_ME = lookupMethod(CS_CLS, "getHandle");
    // nms.DedicatedPlayerList
    private static final Class<?> DPL_CLS = lookupClass(NMS_PACKAGE + "DedicatedPlayerList");
    // DedicatedServer DedicatedPlayerList#getServer()
    private static final Method DPL_GET_SERVER_ME = lookupMethod(DPL_CLS, "getServer");
    // nms.MinecraftServer
    private static final Class<?> MS_CLS = lookupClass(NMS_PACKAGE + "MinecraftServer");
    // ServerConnection MinecraftServer#getServerConnection()
    private static final Method DS_GET_SERVER_CONNECTION = lookupMethod(MS_CLS, "getServerConnection");
    // nms.ServerConnection
    private static final Class<?> SC_CLS = lookupClass(NMS_PACKAGE + "ServerConnection");
    // List<ChannelFuture> ServerConnection#listeningChannels
    private static final Field SERVER_CHANNELS = lookupField(SC_CLS, "listeningChannels");
    // List<NetworkManager> ServerConnection#connectedChannels
    private static final Field CLIENT_CONNECTIONS = lookupField(SC_CLS, "connectedChannels");

    private static final Object SERVER_CONNECTION_INST;

    static {
        Server csInstance = Bukkit.getServer();
        Object dplInstance = invokeMethod(CS_GET_HANDLE_ME, csInstance);
        Object dsInstance = invokeMethod(DPL_GET_SERVER_ME, dplInstance);
        SERVER_CONNECTION_INST = invokeMethod(DS_GET_SERVER_CONNECTION, dsInstance);
    }

    // nms.NetworkManager
    private static final Class<?> NM_CLS = lookupClass(NMS_PACKAGE + "NetworkManager");
    // Channel NetworkManager#channel
    private static final Field NM_CHANNEL = lookupField(NM_CLS, "channel");

    // netty.ChannelHandler
    private static final Class<?> CH_HANDLER_CLS = lookupClass(NETTY_PACKAGE + "ChannelHandler");
    // void ChannelHandler#handlerAdded(ChannelHandlerContext)
    private static final String CH_HANDLER_ADD_ME_NAME = "handlerAdded";

    // netty.ChannelInboundHandler
    private static final Class<?> CIH_CLS = lookupClass(NETTY_PACKAGE + "ChannelInboundHandler");
    // void ChannelInboundHandler#channelRead(ChannelHandlerContext, Object)
    private static final String CIH_CH_READ_ME_NAME = "channelRead";

    // netty.ChannelOutboundHandler
    private static final Class<?> COH_CLS = lookupClass(NETTY_PACKAGE + "ChannelOutboundHandler");
    // void ChannelOutboundHandler#write(ChannelHandlerContext, Object, ChannelPromise)
    private static final String COH_WRITE_ME_NAME = "write";

    // netty.Channel
    private static final Class<?> CH_CLS = lookupClass(NETTY_PACKAGE + "Channel");
    // ChannelPipeline Channel#pipeline()
    private static final Method CH_PIPELINE_ME = lookupMethod(CH_CLS, "pipeline");

    // netty.ChannelPipeline
    private static final Class<?> CP_CLS = lookupClass(NETTY_PACKAGE + "ChannelPipeline");
    // ChannelPipeline ChannelPipeline#addFirst(ChannelHandler...)
    private static final Method CP_ADD_FIRST_ME = lookupMethod(CP_CLS, "addFirst",
            Array.newInstance(CH_HANDLER_CLS, 0).getClass());
    // ChannelPipeline ChannelPipeline#addLast(ChannelHandler...)
    private static final Method CP_ADD_LAST_ME = lookupMethod(CP_CLS, "addLast",
            Array.newInstance(CH_HANDLER_CLS, 0).getClass());
    // ChannelPipeline ChannelPipeline#addBefore(String, String, ChannelHandler)
    private static final Method CP_ADD_BEFORE_ME = lookupMethod(CP_CLS, "addBefore",
            String.class, String.class, CH_HANDLER_CLS);
    // ChannelPipeline#remove(ChannelHandler)
    private static final Method CP_REMOVE_ME = lookupMethod(CP_CLS, "remove",
            CH_HANDLER_CLS);

    // netty.ChannelFuture
    private static final Class<?> CF_CLS = lookupClass(NETTY_PACKAGE + "ChannelFuture");
    // Channel ChannelFuture#channel()
    private static final Method CF_CHANNEL_ME = lookupMethod(CF_CLS, "channel");

    // netty.ChannelHandlerContext
    private static final Class<?> CHC_CLS = lookupClass(NETTY_PACKAGE + "ChannelHandlerContext");
    // Channel ChannelHandlerContext#channel()
    private static final Method CHC_CHANNEL_ME = lookupMethod(CHC_CLS, "channel");
    // EventExecutor ChannelHandlerContext#executor()
    private static final Method CHC_EXECUTOR_ME = lookupMethod(CHC_CLS, "executor");
    // ChannelPipeline ChannelHandlerContext#pipeline()
    private static final Method CHC_PIPELINE_ME = lookupMethod(CHC_CLS, "pipeline");
    // ChannelHandlerContext ChannelHandlerContext#fireChannelRead(Object)
    private static final Method CHC_FIRE_CH_READ_ME = lookupMethod(CHC_CLS, "fireChannelRead",
            Object.class);
    // ChannelHandlerContext ChannelHandlerContext#fireExceptionCaugh(Throwable)
    private static final Method CHC_FIRE_EX_CAUGHT_ME = lookupMethod(CHC_CLS, "fireExceptionCaught",
            Throwable.class);

    // netty.ChannelOutboundInvoker
    private static final Class<?> COI_CLS = lookupClass(NETTY_PACKAGE + "ChannelOutboundInvoker");
    // netty.ChannelPromise
    private static final Class<?> CH_PROMISE_CLS = lookupClass(NETTY_PACKAGE + "ChannelPromise");
    // ChannelFuture ChannelOutboundInvoker#write(Object, ChannelPromise)
    private static final Method COI_WRITE_ME = lookupMethod(COI_CLS, "write",
            Object.class, CH_PROMISE_CLS);

    private static final Map<String, Method> CIH_FORWARD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Method> COH_FORWARD_CACHE = new ConcurrentHashMap<>();

    private static final String MC_PACKET_HANDLER_ID = "packet_handler";

    private static final AtomicInteger UNIQUE_COUNTER = new AtomicInteger();

    private final Map<Object, ClientConnection> connectionMap =
            new MapMaker().weakKeys().makeMap();
    private final Map<UUID, Object> playerMap = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final String packetInProxyId;
    private final String packetOutProxyId;

    private final Listener supportListener;
    private final Object serverConnectionInitProxy;
    private final Object playerConnectionInitProxy;
    private final Object packetInProxy;
    private final Object packetOutProxy;

    private boolean hasBegun;
    private volatile BiFunction<ClientConnection, Object, Object> inHandler;
    private volatile BiFunction<ClientConnection, Object, Object> outHandler;

    public TinierProtocol(Plugin plugin) {
        this.plugin = plugin;

        int uniquifier = UNIQUE_COUNTER.incrementAndGet();
        this.packetInProxyId = plugin.getName() + "_tinierprotocol_in_" + uniquifier;
        this.packetOutProxyId = plugin.getName() + "_tinierprotocol_out_" + uniquifier;

        this.supportListener = this.createSupportListener();
        this.packetInProxy = this.createPacketInProxy();
        this.packetOutProxy = this.createPacketOutProxy();
        this.playerConnectionInitProxy = this.createPlayerConnectionProxy();
        this.serverConnectionInitProxy = this.createConnectionInitProxy();
    }

    public void setInHandler(BiFunction<ClientConnection, Object, Object> inHandler) {
        this.inHandler = inHandler;
    }

    public BiFunction<ClientConnection, Object, Object> getInHandler() {
        return this.inHandler;
    }

    public void setOutHandler(BiFunction<ClientConnection, Object, Object> outHandler) {
        this.outHandler = outHandler;
    }

    public BiFunction<ClientConnection, Object, Object> getOutHandler() {
        return this.outHandler;
    }

    public boolean hasBegun() {
        synchronized (this) {
            return this.hasBegun;
        }
    }

    public void begin() {
        synchronized (this) {
            if (this.hasBegun) {
                return;
            }

            Bukkit.getPluginManager().registerEvents(this.supportListener, this.plugin);
            this.hijackCurrentPlayers();
            this.hijackServerConnection();
            this.hasBegun = true;
        }
    }

    public void close() {
        synchronized (this) {
            if (!this.hasBegun) {
                return;
            }

            HandlerList.unregisterAll(this.supportListener);
            this.connectionMap.clear();
            this.playerMap.clear();

            List<?> channelFutures = getFieldValue(SERVER_CHANNELS, SERVER_CONNECTION_INST);
            synchronized (channelFutures) {
                for (Object future : channelFutures) {
                    Object channelInst = invokeMethod(CF_CHANNEL_ME, future);
                    Object pipelineInst = invokeMethod(CH_PIPELINE_ME, channelInst);
                    invokeMethod(CP_REMOVE_ME, pipelineInst,
                            CH_HANDLER_CLS.cast(this.serverConnectionInitProxy));
                }
            }

            List<?> connections = getFieldValue(CLIENT_CONNECTIONS, SERVER_CONNECTION_INST);
            synchronized (connections) {
                for (Object connection : connections) {
                    Object channelInst = getFieldValue(NM_CHANNEL, connection);
                    Object pipelineInst = invokeMethod(CH_PIPELINE_ME, channelInst);

                    invokeMethod(CP_REMOVE_ME, pipelineInst,
                            CH_HANDLER_CLS.cast(this.packetInProxy));
                    invokeMethod(CP_REMOVE_ME, pipelineInst,
                            CH_HANDLER_CLS.cast(this.packetOutProxy));
                }
            }
            hasBegun = false;
        }
    }

    private void hijackChannel(Object channel) {
        Object pipelineInst = invokeMethod(CH_PIPELINE_ME, channel);

        Object addLastArgs = Array.newInstance(CH_HANDLER_CLS, 1);
        Array.set(addLastArgs, 0, this.playerConnectionInitProxy);

        invokeMethod(CP_ADD_LAST_ME, pipelineInst, addLastArgs);
    }

    private void hijackCurrentPlayers() {
        List<?> connections = getFieldValue(CLIENT_CONNECTIONS, SERVER_CONNECTION_INST);
        synchronized (connections) {
            for (Object connection : connections) {
                Object channelInst = getFieldValue(NM_CHANNEL, connection);
                this.hijackChannel(channelInst);
            }
        }
    }

    private void hijackServerConnection() {
        List<?> channelFutures = getFieldValue(SERVER_CHANNELS, SERVER_CONNECTION_INST);
        synchronized (channelFutures) {
            for (Object future : channelFutures) {
                Object channelInst = invokeMethod(CF_CHANNEL_ME, future);
                Object pipelineInst = invokeMethod(CH_PIPELINE_ME, channelInst);

                Object addFirstArgs = Array.newInstance(CH_HANDLER_CLS, 1);
                Array.set(addFirstArgs, 0, this.serverConnectionInitProxy);

                invokeMethod(CP_ADD_FIRST_ME, pipelineInst, addFirstArgs);
            }
        }
    }

    private Listener createSupportListener() {
        return new Listener() {
            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                Player player = event.getPlayer();
                UUID uuid = player.getUniqueId();
                playerMap.remove(uuid);
            }

            @EventHandler
            public void onDisable(PluginDisableEvent event) {
                Plugin disablingPlugin = event.getPlugin();
                if (plugin.equals(disablingPlugin)) {
                    close();
                }
            }
        };
    }

    private Object handleAdaptedProxyMethods(String methodName, String mappedName, Class<?> mappedCls,
                                             Object[] args, Map<String, Method> cache) {
        switch (methodName) {
            case "ensureNotSharable":
            case "handlerAdded":
            case "handlerRemoved":
                break;
            case "isSharable":
                return false;
            case "exceptionCaught":
                Logger logger = this.plugin.getLogger();
                logger.log(Level.SEVERE,
                        "Exception occurred running " + mappedCls.getSimpleName() + "#" + methodName + "()",
                        (Throwable) args[1]);
                invokeMethod(CHC_FIRE_EX_CAUGHT_ME, args[0], args[1]);
                break;
            default:
                Object[] forwardedArgs = new Object[args.length - 1];
                System.arraycopy(args, 1, forwardedArgs, 0, args.length - 1);

                invokeMethod(cache.computeIfAbsent(mappedName, k -> {
                    for (Method me : mappedCls.getDeclaredMethods()) {
                        if (me.getName().equals(k)) {
                            return me;
                        }
                    }

                    throw new UnsupportedOperationException(methodName);
                }), args[0], forwardedArgs);
                break;
        }
        return null;
    }

    private Object createConnectionInitProxy() {
        return Proxy.newProxyInstance(CIH_CLS.getClassLoader(),
                new Class[]{CIH_CLS},
                (o, method, args) -> {
                    String methodName = method.getName();
                    if (methodName.equals(CIH_CH_READ_ME_NAME)) {
                        Object ctx = args[0];
                        Object channel = args[1];

                        this.hijackChannel(channel);

                        invokeMethod(CHC_FIRE_CH_READ_ME, ctx, channel);
                    } else {
                        String mappedName = "fire" + Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
                        return this.handleAdaptedProxyMethods(methodName, mappedName, CHC_CLS,
                                args, CIH_FORWARD_CACHE);
                    }

                    return null;
                });
    }

    private Object createPlayerConnectionProxy() {
        return Proxy.newProxyInstance(CH_HANDLER_CLS.getClassLoader(),
                new Class[]{CH_HANDLER_CLS},
                (o, method, args) -> {
                    String methodName = method.getName();
                    if (methodName.equals(CH_HANDLER_ADD_ME_NAME)) {
                        Object ctx = args[0];

                        Object pipelineInst = invokeMethod(CHC_PIPELINE_ME, ctx);

                        Executor ctxExecutor = invokeMethod(CHC_EXECUTOR_ME, ctx);
                        ctxExecutor.execute(() -> {
                            invokeMethod(CP_ADD_BEFORE_ME, pipelineInst,
                                    MC_PACKET_HANDLER_ID, this.packetInProxyId, this.packetInProxy);
                            invokeMethod(CP_ADD_BEFORE_ME, pipelineInst,
                                    MC_PACKET_HANDLER_ID, this.packetOutProxyId, this.packetOutProxy);
                        });

                        invokeMethod(CP_REMOVE_ME, pipelineInst, o);
                    } else {
                        return this.handleAdaptedProxyMethods(methodName, methodName, CHC_CLS,
                                args, CIH_FORWARD_CACHE);
                    }

                    return null;
                });
    }

    private Object createPacketInProxy() {
        return Proxy.newProxyInstance(CIH_CLS.getClassLoader(),
                new Class[]{CIH_CLS},
                (o, method, args) -> {
                    String methodName = method.getName();
                    if (methodName.equals(CIH_CH_READ_ME_NAME)) {
                        Object ctx = args[0];
                        Object packet = args[1];

                        BiFunction<ClientConnection, Object, Object> handler = this.inHandler;
                        if (handler != null) {
                            Object channel = invokeMethod(CHC_CHANNEL_ME, ctx);
                            ClientConnection cc = this.getClientConnection(channel);

                            packet = handler.apply(cc, packet);
                        }

                        if (packet != null) {
                            invokeMethod(CHC_FIRE_CH_READ_ME, ctx, packet);
                        }

                        return null;
                    } else {
                        String mappedName = "fire" + Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
                        return this.handleAdaptedProxyMethods(methodName, mappedName, CHC_CLS,
                                args, CIH_FORWARD_CACHE);
                    }
                });
    }

    private Object createPacketOutProxy() {
        return Proxy.newProxyInstance(COH_CLS.getClassLoader(),
                new Class[]{COH_CLS},
                (o, method, args) -> {
                    String methodName = method.getName();
                    if (methodName.equals(COH_WRITE_ME_NAME)) {
                        Object ctx = args[0];
                        Object packet = args[1];
                        Object channelPromise = args[2];

                        BiFunction<ClientConnection, Object, Object> handler = this.outHandler;
                        if (handler != null) {
                            Object channel = invokeMethod(CHC_CHANNEL_ME, ctx);
                            ClientConnection cc = this.getClientConnection(channel);

                            packet = handler.apply(cc, packet);
                        }

                        if (packet != null) {
                            invokeMethod(COI_WRITE_ME, ctx, packet, channelPromise);
                        }

                        return null;
                    } else {
                        return this.handleAdaptedProxyMethods(methodName, methodName, COI_CLS,
                                args, COH_FORWARD_CACHE);
                    }
                });
    }

    private ClientConnection getClientConnection(Object channel) {
        return this.connectionMap.computeIfAbsent(channel, k -> new ClientConnection(channel));
    }

    public ClientConnection getClientConnection(Player player) {
        Object ch = this.playerMap.computeIfAbsent(player.getUniqueId(), k -> {
            Object nmsPCon = ClientConnection.getNmsPCon(player);
            return ClientConnection.getChannel(nmsPCon);
        });

        return this.connectionMap.computeIfAbsent(ch, k -> {
            ClientConnection con = new ClientConnection(ch);

            Object nmsPCon = ClientConnection.getNmsPCon(player);
            con.setPlayer(player, nmsPCon);
            return con;
        });
    }

    public static class ClientConnection {
        // obc.entity.CraftPlayer
        private static final Class<?> CRAFT_PLAYER_CLS = lookupClass(OBC_PACKAGE + "entity.CraftPlayer");
        // EntityPlayer CraftPlayer#getHandle()
        private static final Method CRAFT_PLAYER_GET_HANDLE_ME = lookupMethod(CRAFT_PLAYER_CLS, "getHandle");
        // nms.EntityPlayer
        private static final Class<?> ENTITY_PLAYER_CLS = lookupClass(NMS_PACKAGE + "EntityPlayer");
        // PlayerConnection EntityPlayer#playerConnection
        private static final Field ENTITY_PLAYER_PC = lookupField(ENTITY_PLAYER_CLS, "playerConnection");

        // nms.PlayerConnection
        private static final Class<?> PC_CLS = lookupClass(NMS_PACKAGE + "PlayerConnection");
        // nms.Packet
        private static final Class<?> PACKET_CLS = lookupClass(NMS_PACKAGE + "Packet");
        // void PlayerConnection#sendPacket(Packet)
        private static final Method PC_SEND_PACKET_ME = lookupMethod(PC_CLS, "sendPacket",
                PACKET_CLS);
        // NetworkManager PlayerConnection#networkManager
        private static final Field PC_NETWORK_MANAGER = lookupField(PC_CLS, "networkManager");

        // ChannelFuture ChannelOutboundInvoker#writeAndFlush(Object)
        private static final Method COI_WRITE_AND_FLUSH_ME = lookupMethod(COI_CLS, "writeAndFlush",
                Object.class);

        private final Object channelInst;
        private volatile UUID uuid;
        private volatile Object nmsPCon;

        ClientConnection(Object channelInst) {
            this.channelInst = channelInst;
        }

        static Object getNmsPCon(Player player) {
            Object entityPlayerInst = invokeMethod(CRAFT_PLAYER_GET_HANDLE_ME, player);
            return getFieldValue(ENTITY_PLAYER_PC, entityPlayerInst);
        }

        static Object getChannel(Object nmsPCon) {
            Object networkManagerInst = getFieldValue(PC_NETWORK_MANAGER, nmsPCon);
            return getFieldValue(NM_CHANNEL, networkManagerInst);
        }

        public void sendPacket(Object packet) {
            Object playerConnection = this.nmsPCon;
            if (playerConnection != null) {
                invokeMethod(PC_SEND_PACKET_ME, playerConnection, packet);
            } else {
                invokeMethod(COI_WRITE_AND_FLUSH_ME, this.channelInst, packet);
            }
        }

        public boolean hasPlayer() {
            return this.uuid != null;
        }

        public void setPlayer(Player player, Object nmsPCon) {
            if (player == null) {
                this.uuid = null;
                this.nmsPCon = null;
                return;
            }

            this.uuid = player.getUniqueId();
            this.nmsPCon = nmsPCon;
        }

        public Player getPlayer() {
            UUID uuid = this.uuid;
            if (uuid == null) {
                throw new IllegalStateException("No player");
            }

            return Bukkit.getPlayer(uuid);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            ClientConnection that = (ClientConnection) o;
            return channelInst.equals(that.channelInst);
        }

        @Override
        public int hashCode() {
            return Objects.hash(channelInst);
        }
    }

    // Silenced exception reflection lookup/caller methods

    private static Class<?> lookupClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static Method lookupMethod(Class<?> cls, String methodName, Class<?>... params) {
        try {
            return cls.getDeclaredMethod(methodName, params);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> T invokeMethod(Method method, Object instance, Object... args) {
        try {
            return (T) method.invoke(instance, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field lookupField(Class<?> cls, String fieldName) {
        try {
            Field field = cls.getDeclaredField(fieldName);
            field.setAccessible(true);

            return field;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> T getFieldValue(Field field, Object instance) {
        try {
            return (T) field.get(instance);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
