# `tinierprotocol`

This is an adaptation heavily inspired by Comphenix's
`TinyProtocol` utility, which allows Bukkit developers to
intercept and manipulate packets without needing to rely
on the full facilities offered by ProtocolLib.

This is another project that was partially spurred by
people on the SpigotMC Forums. One common question is how
they can modify packets without using an "external API,"
which although in my opinion, a stupid way of looking at
things, I've had trouble in the past working with the
networking internals in Minecraft, so I thought I'd try to
drop ProtocolLib and figure it out once and for all.

`tinierprotocol` is actually significantly longer than
`TinyProtocol`, the name is meant to signify that this is
a single-class source file that requires no modification
of your pom file or an additional Reflections class. The
primary goal was to avoid using any dependencies other
than the Bukkit API all the while offering the same
features as `TinyProtocol`, including login and ping
packets as wel as bidirectional packet manipulation.

# Implementation

The hardest problem with avoiding any dependencies
(even Netty channels) is that there's virtually no way
to manipulate Netty channels without the availability of
Netty channels in the first place. Luckily, it is possible
to proxy interface classes using `java.lang.reflect.Proxy`.
This class allows you to essentially "listen" for calls to
interface methods and execute logic in place of writing
an actual implementation of the interface. The abstractions
in Netty such as `ChannelHandler`s and the inbound and
outbound versions of them provided an ideal entry point for
the proxy classes, allowing the channel input and output
to be easily intercepted by `tinierprotocol`.

Having figured out how to essentially write classes without
having to depend on Netty channels, the second hardest
issue is determining how to inject them into the Minecraft
networking backend. The entry point for all the networking
code is in `ServerConnection`, which contains 2
collections: a singleton list containing the
`ChannelFuture` for the socket and another list containing
`NetworkManager`s, which are client connections associated
with each player connected to the server. The basic idea is
to intercept any connections initiated to the server socket
as soon as it is available. A proxied
`ChannelInboundHandler` does the trick here, since a
`channelRead` indicates that a new connection will be
initiated to handle the communication that a client
initiates. From there, the channel will need to be
manipulated to accept a `ChannelHandler` to intercept
traffic between the server and the corresponding client.
Simple as this may seem, this is no trivial task. I
attempted to figure it out on my own, but I ended up
giving up because I honestly couldn't figure out why the
channel behaves the way it does and followed the same
technique as `TinyProtocol`. The process goes like this:

  1) a `ChannelHandler` is added to the end of the new
  channel's pipeline 
  2) the tail `ChannelHandler` schedules
  a task 
  3) the task adds the `ChannelHandlers` actually
  listening to traffic to the `Channel`.
   
My guess is that the task is needed to ensure that the 
other `ChannelHandler`s are added (since you don't want to
be decoding the packet bytes yourself, you want to
piggyback off of the server), but I'm not really sure
about the need to have a separate `ChannelHandler` do that
(step 2) rather than the the `ChannelHandler` doing the
initialization (the caller of step 1). It seems that Netty
doesn't even activate the channel without steps 1 and 2 for
some reason. Someone will need to explain that to me.

Another major issue with proxies is that you cannot
implement classes, even abstract classes. They must be
interfaces, meaning that every method needs to run the
default logic for Netty to work properly, since
`ChannelHandlers` pass events off to the next
`ChannelHandler`. I decided just to base it off of
reflective lookups to map methods to the right
"pass-off" method in the `ChannelHandlerContext` rather
than bothering to manually map them. It ended up working
quite well, and there is even a cache for the methods in
place to improve reflective performance.

One small issue with proxied objects is that they seem to
have identity issues when being passed to method
parameters. A simple reflective cast fixes the issue,
however.

The end result of putting this all together is literally
one class that you can copy and paste into your plugin and
use to intercept packets without making any modifications
to your POM or build.gradle or whatever. Again, this I
personally think this is incredibly stupid and dumb and
that you should use an API whenever possible for a number
of reasons, but if you were genuinely curious
(like myself), then yes, with a little bit of reflective
hacking here and there, you can indeed avoid the
*dreaded* external dependency...

# Build The Test Jar

This is NOT intended to be a plugin! This doesn't do
anything except sit in your plugins folder. This is an API
that developers are supposed to use in their plugins. The
purpose of this section is mostly to remind myself to use
`shadowJar` rather than `jar`.

``` shell
git clone https://github.com/AgentTroll/tinierprotocol.git
cd tinierprotocol
./gradlew clean shadowJar
```

# Caveats

  * Not production-ready. This hasn't been extensively
  tested (and will never be). This is a proof-of-concept.
  * Not performance-tested. I am not currently interested
  in the performance of reflective proxies. That being
  said, a bytecode-generation based reflection API might
  offer significant performance gains at very little cost.
  * This is not a drop-in replacement for TinyProtocol or
  meant to be used in the place of ProtocolLib. Please
  depend on ProtocolLib! It makes your life so much easier
  and the ability to use an API is more attractive to
  employers in the Minecraft ecosystem!
  * This is written for 1.15 ONLY. There is no backwards or
  forwards compatibility built into the reflection. The
  only purpose of the reflection is to avoid having to
  include the "external dependencies" I was referring to
  earlier in the README.

# Credits

Built with [IntelliJ IDEA]()

Inspired by [TinyProtocol](https://github.com/aadnk/ProtocolLib/blob/master/modules/TinyProtocol/src/main/java/com/comphenix/tinyprotocol/TinyProtocol.java)
