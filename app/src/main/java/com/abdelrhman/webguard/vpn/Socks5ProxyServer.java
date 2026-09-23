package com.abdelrhman.webguard.vpn;

import android.net.VpnService;

import com.abdelrhman.webguard.core.DomainBlocker;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local SOCKS5 gateway used by the tun2socks engine.
 * Every outbound socket is protected so it cannot recurse into the VPN.
 */
public final class Socks5ProxyServer {
    private static final int PORT = 1080;

    private final VpnService vpn;
    private final android.content.Context context;
    private final DnsClient dns;

    private volatile boolean running;
    private ServerSocket server;
    private Thread acceptThread;

    public Socks5ProxyServer(
            VpnService vpn,
            android.content.Context context) {
        this.vpn = vpn;
        this.context = context;
        this.dns = new DnsClient(vpn);
    }

    public synchronized void start() throws IOException {
        if (running) return;

        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", PORT));
        running = true;

        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    final Socket client = server.accept();
                    Thread worker = new Thread(
                            () -> handleClient(client),
                            "WebGuard-SOCKS-Client");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException error) {
                    if (running) {
                        error.printStackTrace();
                    }
                }
            }
        }, "WebGuard-SOCKS-Accept");

        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
    }

    private void handleClient(Socket client) {
        try (Socket socket = client) {
            socket.setTcpNoDelay(true);

            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            if (!handshake(input, output)) {
                return;
            }

            int version = input.read();
            int command = input.read();
            int reserved = input.read();
            int addressType = input.read();

            if (version != 5 || reserved != 0 ||
                    command < 0 || addressType < 0) {
                return;
            }

            Destination destination =
                    readDestination(input, addressType);

            if (destination == null) {
                sendReply(output, 0x08, null, 0);
                return;
            }

            if (command == 1) {
                handleConnect(socket, output, destination);
            } else if (command == 3) {
                handleUdpAssociate(socket, output);
            } else {
                sendReply(output, 0x07, null, 0);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean handshake(InputStream input, OutputStream output)
            throws IOException {

        int version = input.read();
        int methodCount = input.read();

        if (version != 5 || methodCount < 0 || methodCount > 255) {
            return false;
        }

        byte[] methods = readFully(input, methodCount);
        boolean noAuth = false;

        for (byte method : methods) {
            if ((method & 0xFF) == 0x00) {
                noAuth = true;
                break;
            }
        }

        output.write(new byte[]{
                0x05,
                (byte) (noAuth ? 0x00 : 0xFF)
        });
        output.flush();

        return noAuth;
    }

    private Destination readDestination(
            InputStream input,
            int addressType) throws IOException {

        String host;

        if (addressType == 1) {
            byte[] address = readFully(input, 4);
            host = (address[0] & 255) + "." +
                    (address[1] & 255) + "." +
                    (address[2] & 255) + "." +
                    (address[3] & 255);
        } else if (addressType == 3) {
            int length = input.read();
            if (length < 1 || length > 255) {
                return null;
            }
            host = new String(
                    readFully(input, length),
                    StandardCharsets.US_ASCII);
        } else if (addressType == 4) {
            byte[] address = readFully(input, 16);
            host = InetAddress.getByAddress(address).getHostAddress();
        } else {
            return null;
        }

        int high = input.read();
        int low = input.read();

        if (high < 0 || low < 0) {
            return null;
        }

        return new Destination(
                host,
                (high << 8) | low,
                addressType == 3);
    }

    private void handleConnect(
            Socket client,
            OutputStream clientOutput,
            Destination destination) {

        if (destination.isDomain &&
                DomainBlocker.isBlocked(context, destination.host)) {

            BlockLog.record(context, destination.host);
            safeReply(clientOutput, 0x02);
            return;
        }

        try {
            InetAddress[] addresses = destination.isDomain
                    ? dns.resolve(destination.host)
                    : new InetAddress[]{
                    InetAddress.getByName(destination.host)
            };

            Socket remote = null;

            for (InetAddress address : addresses) {
                try {
                    Socket candidate = new Socket();
                    candidate.setTcpNoDelay(true);

                    if (!vpn.protect(candidate)) {
                        candidate.close();
                        throw new IOException(
                                "Could not protect outbound socket");
                    }

                    candidate.connect(
                            new InetSocketAddress(address, destination.port),
                            7000);

                    remote = candidate;
                    break;
                } catch (IOException ignored) {
                }
            }

            if (remote == null) {
                safeReply(clientOutput, 0x04);
                return;
            }

            try (Socket upstream = remote) {
                InetSocketAddress local =
                        (InetSocketAddress) upstream.getLocalSocketAddress();

                sendReply(
                        clientOutput,
                        0x00,
                        local.getAddress().getAddress(),
                        local.getPort());

                relay(client, upstream);
            }
        } catch (Exception ignored) {
            safeReply(clientOutput, 0x05);
        }
    }

    private void handleUdpAssociate(
            Socket control,
            OutputStream output) {

        DatagramSocket udp = null;
        AtomicBoolean closed = new AtomicBoolean(false);

        try {
            udp = new DatagramSocket(
                    new InetSocketAddress("127.0.0.1", 0));

            if (!vpn.protect(udp)) {
                throw new IOException(
                        "Could not protect outbound UDP socket");
            }

            final DatagramSocket socket = udp;

            sendReply(
                    output,
                    0x00,
                    socket.getLocalAddress().getAddress(),
                    socket.getLocalPort());

            InetSocketAddress clientAddress = null;
            byte[] buffer = new byte[65535];

            while (running &&
                    !control.isClosed() &&
                    !closed.get()) {

                DatagramPacket packet =
                        new DatagramPacket(buffer, buffer.length);

                socket.receive(packet);

                UdpRequest request =
                        parseUdpRequest(packet.getData(), packet.getLength());

                if (request == null) {
                    continue;
                }

                if (request.domain != null &&
                        DomainBlocker.isBlocked(context, request.domain)) {
                    BlockLog.record(context, request.domain);
                    continue;
                }

                InetAddress target;

                if (request.domain != null) {
                    InetAddress[] addresses =
                            dns.resolve(request.domain);

                    if (addresses.length == 0) {
                        continue;
                    }

                    target = addresses[0];
                } else {
                    target = request.address;
                }

                socket.send(new DatagramPacket(
                        request.payload,
                        request.payload.length,
                        target,
                        request.port));

                clientAddress = new InetSocketAddress(
                        packet.getAddress(),
                        packet.getPort());

                socket.setSoTimeout(1200);

                try {
                    DatagramPacket response =
                            new DatagramPacket(buffer, buffer.length);
                    socket.receive(response);

                    if (clientAddress != null) {
                        byte[] wrapped =
                                wrapUdpResponse(response);

                        socket.send(new DatagramPacket(
                                wrapped,
                                wrapped.length,
                                clientAddress.getAddress(),
                                clientAddress.getPort()));
                    }
                } catch (SocketTimeoutException ignored) {
                } finally {
                    socket.setSoTimeout(0);
                }
            }
        } catch (Exception ignored) {
        } finally {
            closed.set(true);
            if (udp != null) {
                udp.close();
            }
        }
    }

    private static UdpRequest parseUdpRequest(
            byte[] data,
            int length) throws IOException {

        if (length < 10 ||
                data[0] != 0 ||
                data[1] != 0 ||
                data[2] != 0) {
            return null;
        }

        int offset = 3;
        int addressType = data[offset++] & 255;

        InetAddress address = null;
        String domain = null;

        if (addressType == 1) {
            if (offset + 4 > length) return null;

            address = InetAddress.getByAddress(
                    Arrays.copyOfRange(data, offset, offset + 4));
            offset += 4;
        } else if (addressType == 3) {
            if (offset >= length) return null;

            int domainLength = data[offset++] & 255;

            if (domainLength < 1 ||
                    offset + domainLength > length) {
                return null;
            }

            domain = new String(
                    data,
                    offset,
                    domainLength,
                    StandardCharsets.US_ASCII)
                    .toLowerCase(Locale.US);

            offset += domainLength;
        } else if (addressType == 4) {
            if (offset + 16 > length) return null;

            address = InetAddress.getByAddress(
                    Arrays.copyOfRange(data, offset, offset + 16));
            offset += 16;
        } else {
            return null;
        }

        if (offset + 2 > length) {
            return null;
        }

        int port =
                ((data[offset] & 255) << 8) |
                (data[offset + 1] & 255);

        offset += 2;

        return new UdpRequest(
                address,
                domain,
                port,
                Arrays.copyOfRange(data, offset, length));
    }

    private static byte[] wrapUdpResponse(
            DatagramPacket response) throws IOException {

        byte[] address = response.getAddress().getAddress();
        int addressType = address.length == 4 ? 1 : 4;

        ByteArrayOutputStream output =
                new ByteArrayOutputStream(
                        response.getLength() + 32);

        output.write(0);
        output.write(0);
        output.write(0);
        output.write(addressType);
        output.write(address);

        output.write((response.getPort() >>> 8) & 255);
        output.write(response.getPort() & 255);

        output.write(
                response.getData(),
                response.getOffset(),
                response.getLength());

        return output.toByteArray();
    }

    private static void relay(Socket client, Socket upstream) {
        Thread clientToUpstream =
                new Thread(
                        () -> copy(client, upstream),
                        "WebGuard-relay-A");

        Thread upstreamToClient =
                new Thread(
                        () -> copy(upstream, client),
                        "WebGuard-relay-B");

        clientToUpstream.setDaemon(true);
        upstreamToClient.setDaemon(true);

        clientToUpstream.start();
        upstreamToClient.start();

        try {
            clientToUpstream.join();
        } catch (InterruptedException ignored) {
        }

        try {
            upstreamToClient.join(250);
        } catch (InterruptedException ignored) {
        }
    }

    private static void copy(Socket from, Socket to) {
        try {
            InputStream input = from.getInputStream();
            OutputStream output = to.getOutputStream();

            byte[] buffer = new byte[16 * 1024];
            int count;

            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                output.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try {
                from.shutdownInput();
            } catch (Exception ignored) {
            }

            try {
                to.shutdownOutput();
            } catch (Exception ignored) {
            }

            try {
                from.close();
            } catch (Exception ignored) {
            }

            try {
                to.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static byte[] readFully(
            InputStream input,
            int length) throws IOException {

        byte[] data = new byte[length];
        int offset = 0;

        while (offset < length) {
            int count = input.read(
                    data,
                    offset,
                    length - offset);

            if (count < 0) {
                throw new EOFException();
            }

            offset += count;
        }

        return data;
    }

    private static void sendReply(
            OutputStream output,
            int reply,
            byte[] address,
            int port) throws IOException {

        byte[] actualAddress =
                (address == null || address.length == 0)
                        ? new byte[]{0, 0, 0, 0}
                        : address;

        int addressType =
                actualAddress.length == 16 ? 4 : 1;

        output.write(new byte[]{
                0x05,
                (byte) reply,
                0,
                (byte) addressType
        });

        output.write(actualAddress);
        output.write((port >>> 8) & 255);
        output.write(port & 255);
        output.flush();
    }

    private static void safeReply(
            OutputStream output,
            int reply) {

        try {
            sendReply(
                    output,
                    reply,
                    new byte[]{0, 0, 0, 0},
                    0);
        } catch (Exception ignored) {
        }
    }

    private static final class Destination {
        final String host;
        final int port;
        final boolean isDomain;

        Destination(
                String host,
                int port,
                boolean isDomain) {
            this.host = host;
            this.port = port;
            this.isDomain = isDomain;
        }
    }

    private static final class UdpRequest {
        final InetAddress address;
        final String domain;
        final int port;
        final byte[] payload;

        UdpRequest(
                InetAddress address,
                String domain,
                int port,
                byte[] payload) {
            this.address = address;
            this.domain = domain;
            this.port = port;
            this.payload = payload;
        }
    }
}
