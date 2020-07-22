package io.github.agenttroll.tinierprotocol;

import com.google.common.collect.MapMaker;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
 *
 * <p>This class is not meant to be a drop-in replacement,
 * but it was heavily inspired by TinyProtocol
 * nonetheless.</p>
 *
 * <p> You can find the source for that class here:
 * https://github.com/aadnk/ProtocolLib/blob/master/modules/TinyProtocol/src/main/java/com/comphenix/tinyprotocol/TinyProtocol.java
 * </p>
 *
 * <p>This class provides reflective packet interception of
 * both clientbound and serverbound packets, as well as
 * login and ping packets. Support is also provided for
 * sending packets.</p>
 *
 * <p>Caveats:
 * - This is not intended to be for production use. This is
 * a proof-of-concept ONLY. Use at your own risk
 * - I have not thoroughly investigated the performance hit
 * taken from the use of reflective Proxies, but I suspect
 * that it is non-trivial</p>
 */
public class TinierProtocol {
    // Versioned package prefixes
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

    // Initialize the ServerConnection instance held by the server
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
    // SocketAddress Channel#remoteAddress()
    private static final Method CH_REMOTE_ADDR_ME = lookupMethod(CH_CLS, "remoteAddress");
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

    // Caches for reflective methods forwarded from ChannelInboundHandler and
    // ChannelOutboundHandler
    private static final Map<String, Method> CIH_FORWARD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Method> COH_FORWARD_CACHE = new ConcurrentHashMap<>();

    // The ID of the ChannelHandler before which to insert the TinierProtocol
    // interceptor handlers
    private static final String MC_PACKET_HANDLER_ID = "packet_handler";

    // A uniquifier number used for multiple instances in a single plugin
    private static final AtomicInteger UNIQUE_COUNTER = new AtomicInteger();

    // Cache for InetAddress-Channel used to find a player when they login
    private final Map<InetAddress, Object> addressMap =
            new MapMaker().weakValues().makeMap();
    // Caches for Channel-ClientConnection lookups from the interceptors
    private final Map<Object, ClientConnection> connectionMap =
            new MapMaker().weakKeys().makeMap();
    // The player UUID-Channel lookup cache
    private final Map<UUID, Object> playerMap = new ConcurrentHashMap<>();

    // Plugin instance
    private final Plugin plugin;
    // Packet interceptor handler IDs
    private final String packetInProxyId;
    private final String packetOutProxyId;

    // Instances of proxies and listeners that need to be cleaned up
    // when this thing is closed
    private final Listener supportListener;
    private final Object serverConnectionInitProxy;
    private final Object playerConnectionInitProxy;
    private final Object packetInProxy;
    private final Object packetOutProxy;

    // Whether or not this class is currently intercepting packets
    private boolean hasBegun;

    // The handlers used to perform interception logic
    private volatile BiFunction<ClientConnection, Object, Object> inHandler;
    private volatile BiFunction<ClientConnection, Object, Object> outHandler;

    /**
     * Creates a new instance of {@code TinierProtocol} for
     * use by the given plugin.
     *
     * @param plugin the plugin for which to create the instance
     */
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

    /**
     * Sets the inbound packets (packets coming from the
     * client and to the server) handler.
     *
     * <p>The function has 2 inputs, the connection from
     * which the packet was sent and the packet itself.
     * This is the NMS instance of the packet. The return
     * type will usually be the packet that was given in
     * the input. If you do not want the packet to be
     * handled, simply return {@code null} instead</p>
     *
     * @param inHandler the funciton which handles packets
     */
    public void setInHandler(BiFunction<ClientConnection, Object, Object> inHandler) {
        this.inHandler = inHandler;
    }

    /**
     * Obtains the incoming (serverbound) packet handler
     * that is currently set. This may return null.
     *
     * @return the instance of incoming packet handler
     */
    public BiFunction<ClientConnection, Object, Object> getInHandler() {
        return this.inHandler;
    }

    /**
     * Sets the outbound packets (packets going from the
     * server to the client) handler.
     *
     * <p>The function has 2 inputs, the connection to
     * which the packet will be sent and the packet itself.
     * This is the NMS instance of the packet. The return
     * type will usually be the packet that was given in
     * the input. If you do not want the packet to be sent,
     * simply return {@code null} instead</p>
     *
     * @param outHandler the funciton which handles packets
     */
    public void setOutHandler(BiFunction<ClientConnection, Object, Object> outHandler) {
        this.outHandler = outHandler;
    }

    /**
     * Obtains the outgoing (clientbound) packet handler
     * that is currently set. This may return null.
     *
     * @return the instance of the outgoing packet handler
     */
    public BiFunction<ClientConnection, Object, Object> getOutHandler() {
        return this.outHandler;
    }

    /**
     * Determins whether this class is actively
     * intercepting packets.
     *
     * @return {@code true} if this instance has performed
     * the injection and is listening for packets
     */
    public boolean hasBegun() {
        synchronized (this) {
            return this.hasBegun;
        }
    }

    /**
     * Begins, if not already, the listening by injecting
     * the proper channel listeners into the Minecraft
     * server network backend.
     */
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

    /**
     * Closes, if not already, the listeners by removing
     * them from every player and channel that was
     * previously injected and cleans up any listeners and
     * caches.
     *
     * <p>Note that this DOES NOT clear the handlers set
     * using {@link #setInHandler(BiFunction)} or
     * {@link #setOutHandler(BiFunction)}</p>
     */
    public void close() {
        synchronized (this) {
            if (!this.hasBegun) {
                return;
            }

            // Clean up
            HandlerList.unregisterAll(this.supportListener);
            this.connectionMap.clear();
            this.playerMap.clear();

            // Remove the new connection intializer from
            // the server's connection
            List<?> channelFutures = getFieldValue(SERVER_CHANNELS, SERVER_CONNECTION_INST);
            synchronized (channelFutures) {
                for (Object future : channelFutures) {
                    Object channelInst = invokeMethod(CF_CHANNEL_ME, future);
                    Object pipelineInst = invokeMethod(CH_PIPELINE_ME, channelInst);
                    invokeMethod(CP_REMOVE_ME, pipelineInst,
                            CH_HANDLER_CLS.cast(this.serverConnectionInitProxy));
                }
            }

            // Remove the interceptors from each individual
            // player's connection
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

    /**
     * Performs the injection on a single netty Channel.
     *
     * @param channel the channel which to inject
     */
    private void hijackChannel(Object channel) {
        Object pipelineInst = invokeMethod(CH_PIPELINE_ME, channel);

        Object addLastArgs = Array.newInstance(CH_HANDLER_CLS, 1);
        Array.set(addLastArgs, 0, this.playerConnectionInitProxy);

        // addLast the connection initializer, which in turn
        // adds the interceptors
        invokeMethod(CP_ADD_LAST_ME, pipelineInst, addLastArgs);
    }

    /**
     * Performs the injection on the current active
     * connections to the server.
     */
    private void hijackCurrentPlayers() {
        List<?> connections = getFieldValue(CLIENT_CONNECTIONS, SERVER_CONNECTION_INST);
        synchronized (connections) {
            // Go through each player and add the
            // interceptor to each connection
            for (Object connection : connections) {
                Object channelInst = getFieldValue(NM_CHANNEL, connection);
                this.hijackChannel(channelInst);
            }
        }
    }

    /**
     * Performs the injection on the server's socket
     * connection, thereby automatically injecting any
     * new connections made to the server by clients.
     */
    private void hijackServerConnection() {
        List<?> channelFutures = getFieldValue(SERVER_CHANNELS, SERVER_CONNECTION_INST);
        synchronized (channelFutures) {
            for (Object future : channelFutures) {
                Object channelInst = invokeMethod(CF_CHANNEL_ME, future);
                Object pipelineInst = invokeMethod(CH_PIPELINE_ME, channelInst);

                Object addFirstArgs = Array.newInstance(CH_HANDLER_CLS, 1);
                Array.set(addFirstArgs, 0, this.serverConnectionInitProxy);

                // addFirst the new connection initializer
                invokeMethod(CP_ADD_FIRST_ME, pipelineInst, addFirstArgs);
            }
        }
    }

    /**
     * Creates the Bukkit listener which supplements the
     * packet listeners.
     *
     * <p>This listener will clear any cache entries
     * for player-channel and will disable (close) this
     * instance of TinierProtocol if the plugin that
     * instantiated it is disabled.</p>
     */
    private Listener createSupportListener() {
        return new Listener() {
            @EventHandler
            public void onLogin(PlayerLoginEvent event) {
                Player player = event.getPlayer();

                // PlayerLoginEvent is pretty much the earliest
                // event we can grab the Player object from
                // PlayerList#attemptLogin(...)
                // Pre-cache the player in the client connection
                InetAddress addr = event.getAddress();
                Object ch = addressMap.remove(addr);
                if (ch != null) {
                    playerMap.put(player.getUniqueId(), ch);

                    ClientConnection cc = new ClientConnection(ch);
                    cc.setPlayer(player);
                    connectionMap.put(ch, cc);
                }
            }

            @EventHandler
            public void onJoin(PlayerJoinEvent event) {
                Player player = event.getPlayer();

                // For some unknown reason, the player
                // connection isn't initialized with the
                // player so we are forced to initialize
                // it here...
                Object nmsPCon = ClientConnection.getNmsPCon(player);
                getClientConnection(player).setPlayerConnection(nmsPCon);
            }

            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                Player player = event.getPlayer();
                UUID uuid = player.getUniqueId();

                // Remove cached player
                playerMap.remove(uuid);
            }

            @EventHandler
            public void onDisable(PluginDisableEvent event) {
                Plugin disablingPlugin = event.getPlugin();

                // If the owner plugin is disabled, close
                // and cleanup
                if (plugin.equals(disablingPlugin)) {
                    close();
                }
            }
        };
    }

    /**
     * This method is used to handle forwarding method
     * calls from a proxy to the appropriate method
     * in the channel context in order to pass the
     * arguments down the pipeline.
     *
     * @param methodName the name of the method that is
     * being forwarded
     * @param mappedName the name of the method that should
     * be called
     * @param mappedCls the class to which the mapped
     * method belongs
     * @param args the args to forward
     * @param cache the cache used to store the forwarded
     * method mapping
     * @return the return type of the method that the to
     * which the call was forwarded
     */
    private Object handleAdaptedProxyMethods(String methodName, String mappedName, Class<?> mappedCls,
                                             Object[] args, Map<String, Method> cache) {
        switch (methodName) {
            // These shouldn't do anything
            case "ensureNotSharable":
            case "handlerAdded":
            case "handlerRemoved":
                break;
            // Not sharable, everyone should get their own
            // instance of the channel handlers being
            // proxied just for simplicity
            case "isSharable":
                return false;
            // The default error handler sucks, log
            // using the owner plugin's logger
            case "exceptionCaught":
                Logger logger = this.plugin.getLogger();
                logger.log(Level.SEVERE,
                        "Exception occurred running " + mappedCls.getSimpleName() + "#" + methodName + "()",
                        (Throwable) args[1]);
                invokeMethod(CHC_FIRE_EX_CAUGHT_ME, args[0], args[1]);
                break;
            // Otherwise, it's one of the
            // inbound/outbound specific methods that
            // need to be invoked
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

    /**
     * Creates a new connection initializer proxy which
     * is a ChannelInboundHandler that adds the initializer
     * for any connections initiated to the server.
     *
     * @return the ChannelInboundHandler proxy handling new
     * client-initiated connections
     */
    private Object createConnectionInitProxy() {
        return Proxy.newProxyInstance(CIH_CLS.getClassLoader(),
                new Class<?>[]{CIH_CLS},
                (o, method, args) -> {
                    String methodName = method.getName();

                    // channelRead() called by a new channel
                    if (methodName.equals(CIH_CH_READ_ME_NAME)) {
                        Object ctx = args[0];
                        Object channel = args[1];

                        // Cache for later lookup to find the player object
                        InetSocketAddress addr = invokeMethod(CH_REMOTE_ADDR_ME, channel);
                        this.addressMap.put(addr.getAddress(), channel);

                        // Inject the new channel
                        this.hijackChannel(channel);

                        // Pass to the next ChannelHandler
                        invokeMethod(CHC_FIRE_CH_READ_ME, ctx, channel);
                    } else {
                        String mappedName = "fire" + Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
                        return this.handleAdaptedProxyMethods(methodName, mappedName, CHC_CLS,
                                args, CIH_FORWARD_CACHE);
                    }

                    return null;
                });
    }

    /**
     * Creates a proxy that implements ChannelHandler which
     * actually performs the initialization logic for the
     * new connection.
     *
     * @return the ChannelHandler that initializes a new
     * channel
     */
    private Object createPlayerConnectionProxy() {
        return Proxy.newProxyInstance(CH_HANDLER_CLS.getClassLoader(),
                new Class<?>[]{CH_HANDLER_CLS},
                (o, method, args) -> {
                    String methodName = method.getName();
                    if (methodName.equals(CH_HANDLER_ADD_ME_NAME)) {
                        Object ctx = args[0];

                        Object pipelineInst = invokeMethod(CHC_PIPELINE_ME, ctx);

                        // Delay the task using the event loop executor
                        Executor ctxExecutor = invokeMethod(CHC_EXECUTOR_ME, ctx);
                        ctxExecutor.execute(() -> {
                            // Add the interceptors to the pipeline
                            invokeMethod(CP_ADD_BEFORE_ME, pipelineInst,
                                    MC_PACKET_HANDLER_ID, this.packetInProxyId, this.packetInProxy);
                            invokeMethod(CP_ADD_BEFORE_ME, pipelineInst,
                                    MC_PACKET_HANDLER_ID, this.packetOutProxyId, this.packetOutProxy);
                        });

                        // Remove this initializer proxy from the pipeline
                        invokeMethod(CP_REMOVE_ME, pipelineInst, o);
                    } else {
                        return this.handleAdaptedProxyMethods(methodName, methodName, CHC_CLS,
                                args, CIH_FORWARD_CACHE);
                    }

                    return null;
                });
    }

    /**
     * Creates the ChannelInboundHandler proxy which
     * intercepts incoming (serverbound) packets from
     * the connection and passes it to the inbound
     * packet handler.
     *
     * @return the ChannelInboundHandler that handles
     * serverbound packets
     */
    private Object createPacketInProxy() {
        return Proxy.newProxyInstance(CIH_CLS.getClassLoader(),
                new Class<?>[]{CIH_CLS},
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

    /**
     * Creates a new ChannelOutboundHandler proxy which
     * handles outgoing (clientbound) packets and passes
     * them to the outgoing packet handler.
     *
     * @return the ChannelOutboundHandler proxy that
     * handles outgoing packets
     */
    private Object createPacketOutProxy() {
        return Proxy.newProxyInstance(COH_CLS.getClassLoader(),
                new Class<?>[]{COH_CLS},
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

    /**
     * Obtains the client connection cached for a given
     * Netty Channel object.
     *
     * <p>This method initializes the client connection
     * wrapper if it does not yet exist and is thereby
     * non-null</p>
     *
     * @return the ClientConnection cached for the given
     * channel
     */
    private ClientConnection getClientConnection(Object channel) {
        return this.connectionMap.computeIfAbsent(channel, k -> new ClientConnection(channel));
    }

    /**
     * Obtains a wrapper ClientConnection that represents
     * a client's connection to the server from the given
     * player.
     *
     * <p>This method initializes the client connection if
     * it has not yet been cached and is thereby non-null
     * </p>
     *
     * @param player the player whose connection wrapper to
     * obtain
     * @return the cached connection wrapper for the player
     */
    public ClientConnection getClientConnection(Player player) {
        // Map to the Netty channel object
        Object ch = this.playerMap.computeIfAbsent(player.getUniqueId(), k -> {
            Object nmsPCon = ClientConnection.getNmsPCon(player);
            return ClientConnection.getChannel(nmsPCon);
        });

        // Map from the channel object to the connection wrapper
        return this.connectionMap.computeIfAbsent(ch, k -> {
            ClientConnection con = new ClientConnection(ch);
            con.setPlayer(player);

            Object nmsPCon = ClientConnection.getNmsPCon(player);
            con.setPlayerConnection(nmsPCon);

            return con;
        });
    }

    /**
     * This is a wrapper class over a client's connection
     * with the server.
     *
     * <p>This class allows users to obtain information
     * about a connection such as whom it belongs to as
     * well as to interact with the connection by
     * sending packets. Note that not every connection is
     * associated with a player connected to the server.
     * </p>
     *
     * <p>This class can be used as a key to a hashtable.
     * </p>
     *
     * <p>This class should be cached in
     * {@code TinierProtocol} and retrieved using methods
     * it provides.</p>
     */
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

        /**
         * Creates a new client connection wraper for the
         * given Netty channel instance.
         *
         * @param channelInst the Netty channel which to
         * wrap
         */
        ClientConnection(Object channelInst) {
            this.channelInst = channelInst;
        }

        /**
         * Obtains the NMS PlayerConnection for the given
         * Bukkit Player object.
         *
         * @param player the player from which to obtain
         * the PlayerConnection object
         * @return the NMS PlayerConnection for the given
         * player
         */
        static Object getNmsPCon(Player player) {
            Object entityPlayerInst = invokeMethod(CRAFT_PLAYER_GET_HANDLE_ME, player);
            return getFieldValue(ENTITY_PLAYER_PC, entityPlayerInst);
        }

        /**
         * Obtains the channel from the given NMS
         * PlayerConnection object.
         *
         * @param nmsPCon the NMS PlayerConnection from
         * which to obtain the Netty channel
         * @return the Netty channel associated with the
         * PlayerConnection
         */
        static Object getChannel(Object nmsPCon) {
            Object networkManagerInst = getFieldValue(PC_NETWORK_MANAGER, nmsPCon);
            return getFieldValue(NM_CHANNEL, networkManagerInst);
        }

        /**
         * Sends the given NMS packet to the connection
         * represented by this client connection.
         *
         * <p>While this prefers to use the
         * NMS PlayerConnection to send the packet, if it
         * is not available because the player is not or
         * will not come online (as they may be simply
         * pinging the server), then the method falls back
         * to using the Netty channel to send the packet.
         * </p>
         *
         * @param packet the NMS packet instance
         */
        public void sendPacket(Object packet) {
            Object playerConnection = this.nmsPCon;
            if (playerConnection != null) {
                invokeMethod(PC_SEND_PACKET_ME, playerConnection, packet);
            } else {
                invokeMethod(COI_WRITE_AND_FLUSH_ME, this.channelInst, packet);
            }
        }

        /**
         * Obtains the Netty channel object wrapped by this
         * client connection.
         *
         * <p>The purpose of this class is to wrap a
         * connection. This method is therefore non-null
         * </p>
         *
         * @return the Netty channel
         */
        public Object getRawChannel() {
            return this.channelInst;
        }

        /**
         * Determines whether this client connection
         * represents a currently connected player.
         *
         * @return {@code true} if the connection
         * represents a player and that player has come
         * online
         */
        public boolean hasPlayer() {
            return this.uuid != null;
        }

        /**
         * Notifies this wrapper that the player associated
         * with the connection has joined the game.
         *
         * @param player the player that joined the game,
         * may be {@code null} to clear the player from the
         * connection wrapper
         */
        private void setPlayer(Player player) {
            if (player == null) {
                this.uuid = null;
                return;
            }

            this.uuid = player.getUniqueId();
        }

        /**
         * Notifies this wrapper that the NMS
         * PlayerConnection has been initialized for this
         * player has been initialized.
         *
         * @param nmsPCon the player connection to set for
         * the player
         */
        private void setPlayerConnection(Object nmsPCon) {
            this.nmsPCon = nmsPCon;
        }

        /**
         * Obtains the player associated with this
         * connection.
         *
         * <p>As not every connection represents a player
         * connected to the server, this method is
         * therefore nullable</p>
         *
         * @return the player, or {@code null} if they
         * have not joined yet
         */
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

    /**
     * Looks up a class using its FQN (Fully Qualified
     * Name).
     *
     * @param className the FQN
     * @return the class that was found
     * @throws RuntimeException if the class was not found
     */
    private static Class<?> lookupClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Looks up a method from the given class and with the
     * given method name and parameters.
     *
     * @param cls the class containing the method
     * @param methodName the name of the method
     * @param params the parameters for the method
     * @return the method object
     * @throws RuntimeException if the method cannot be
     * found
     */
    private static Method lookupMethod(Class<?> cls, String methodName, Class<?>... params) {
        try {
            return cls.getDeclaredMethod(methodName, params);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Calls the given method on the given instance of the
     * enclosing class and with the given arguments.
     *
     * @param method the method to invoke
     * @param instance the instance of the enclosing class
     * on which to call the method, or {@code null} if the
     * method is static
     * @param args the arguments to pass to the method
     * @return the return value of the method invoked
     * @throws RuntimeException if the method cannot be
     * accessed or if an error occurred running the method
     */
    private static <T> T invokeMethod(Method method, Object instance, Object... args) {
        try {
            return (T) method.invoke(instance, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Looks up a field from the given class with the given
     * name.
     *
     * <p>This method always makes the field accessible
     * reflectively</p>
     *
     * @param cls the enclosing class containing the field
     * @param fieldName the name of the field to lookup
     * @return the field object
     * @throws RuntimeException if the field is not found
     */
    private static Field lookupField(Class<?> cls, String fieldName) {
        try {
            Field field = cls.getDeclaredField(fieldName);
            field.setAccessible(true);

            return field;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Obtains the value of the given field with the given
     * instance of the enclosing class.
     *
     * @param field the field to obtain the value
     * @param instance the instance of the enclosing class
     * to determine the value of the field
     * @return the value of the field
     * @throws RuntimeException if the field cannot be
     * accessed
     */
    private static <T> T getFieldValue(Field field, Object instance) {
        try {
            return (T) field.get(instance);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
