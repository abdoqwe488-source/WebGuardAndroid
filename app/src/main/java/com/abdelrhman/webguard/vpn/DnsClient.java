package com.abdelrhman.webguard.vpn;

import android.net.VpnService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small protected DNS client used by the local SOCKS proxy.
 * It avoids recursive resolution through the app's own VPN.
 */
public final class DnsClient {
    private static final String[] SERVERS = {"1.1.1.1", "8.8.8.8", "9.9.9.9"};
    private static final long CACHE_MS = 60_000L;

    private final VpnService vpn;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public DnsClient(VpnService vpn) {
        this.vpn = vpn;
    }

    public InetAddress[] resolve(String host) throws IOException {
        String name = normalize(host);
        Entry cached = cache.get(name);
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            return cached.addresses.clone();
        }

        IOException last = null;
        for (String server : SERVERS) {
            for (int type : new int[]{1, 28}) {
                try {
                    InetAddress[] answers = query(server, name, type);
                    if (answers.length > 0) {
                        Entry entry = new Entry(answers, System.currentTimeMillis() + CACHE_MS);
                        cache.put(name, entry);
                        return answers.clone();
                    }
                } catch (IOException ex) {
                    last = ex;
                }
            }
        }
        throw last != null ? last : new IOException("DNS resolution failed: " + name);
    }

    private InetAddress[] query(String server, String host, int type) throws IOException {
        byte[] packet = buildQuery(host, type);
        DatagramSocket socket = new DatagramSocket();
        try {
            if (!vpn.protect(socket)) {
                throw new IOException("Cannot protect DNS socket");
            }
            socket.setSoTimeout(1800);
            DatagramPacket request = new DatagramPacket(
                    packet, packet.length, InetAddress.getByName(server), 53);
            socket.send(request);

            byte[] buffer = new byte[4096];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);

            return parseAnswers(response.getData(), response.getLength(), type);
        } finally {
            socket.close();
        }
    }

    private byte[] buildQuery(String host, int type) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        int id = random.nextInt(65536);
        out.write((id >>> 8) & 255);
        out.write(id & 255);
        out.write(0x01); out.write(0x00); // RD
        out.write(0x00); out.write(0x01); // QDCOUNT
        out.write(0x00); out.write(0x00); // ANCOUNT
        out.write(0x00); out.write(0x00); // NSCOUNT
        out.write(0x00); out.write(0x00); // ARCOUNT

        String[] labels = host.split("\\.");
        for (String label : labels) {
            byte[] b = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (b.length == 0 || b.length > 63) throw new IOException("Bad DNS label");
            out.write(b.length);
            out.write(b, 0, b.length);
        }
        out.write(0);
        out.write((type >>> 8) & 255); out.write(type & 255);
        out.write(0x00); out.write(0x01); // IN
        return out.toByteArray();
    }

    private InetAddress[] parseAnswers(byte[] data, int len, int wantedType) throws IOException {
        if (len < 12) return new InetAddress[0];
        int qd = u16(data, 4);
        int an = u16(data, 6);
        int offset = 12;

        for (int i = 0; i < qd; i++) {
            offset = skipName(data, len, offset);
            if (offset < 0 || offset + 4 > len) return new InetAddress[0];
            offset += 4;
        }

        List<InetAddress> result = new ArrayList<>();
        for (int i = 0; i < an && offset >= 0 && offset + 10 <= len; i++) {
            offset = skipName(data, len, offset);
            if (offset < 0 || offset + 10 > len) break;

            int type = u16(data, offset); offset += 2;
            int clazz = u16(data, offset); offset += 2;
            offset += 4; // TTL
            int rdLen = u16(data, offset); offset += 2;
            if (offset + rdLen > len) break;

            if (clazz == 1 && type == wantedType &&
                    ((wantedType == 1 && rdLen == 4) || (wantedType == 28 && rdLen == 16))) {
                byte[] address = Arrays.copyOfRange(data, offset, offset + rdLen);
                result.add(InetAddress.getByAddress(address));
            }
            offset += rdLen;
        }
        return result.toArray(new InetAddress[0]);
    }

    private static int skipName(byte[] data, int len, int offset) {
        int guard = 0;
        while (offset >= 0 && offset < len && guard++ < 128) {
            int n = data[offset] & 255;
            if (n == 0) return offset + 1;
            if ((n & 0xC0) == 0xC0) {
                return offset + 2;
            }
            if (n > 63 || offset + 1 + n > len) return -1;
            offset += 1 + n;
        }
        return -1;
    }

    private static int u16(byte[] d, int o) {
        return ((d[o] & 255) << 8) | (d[o + 1] & 255);
    }

    private static String normalize(String host) {
        String s = host == null ? "" : host.trim().toLowerCase(Locale.US);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static final class Entry {
        final InetAddress[] addresses;
        final long expiresAt;

        Entry(InetAddress[] addresses, long expiresAt) {
            this.addresses = addresses;
            this.expiresAt = expiresAt;
        }
    }
}
