package com.abdelrhman.webguard.vpn;

import android.net.VpnService;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Resolver for connections leaving the local VPN.
 * DNS-over-HTTPS is tried first so networks that block direct UDP/53 still work.
 * Every transport socket is protected before connect/send to avoid VPN recursion.
 */
public final class DnsClient {
    private static final long CACHE_MS = 60_000L;
    private static final String DOH_HOST = "cloudflare-dns.com";
    private static final String DOH_IP = "1.1.1.1";
    private static final String[] UDP_SERVERS = {
            "1.1.1.1", "8.8.8.8", "9.9.9.9"
    };

    private final VpnService vpn;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public DnsClient(VpnService vpn) {
        this.vpn = vpn;
    }

    public InetAddress[] resolve(String host) throws IOException {
        String name = normalize(host);
        if (name.isEmpty()) throw new IOException("Empty DNS name");

        Entry entry = cache.get(name);
        if (entry != null && entry.expiresAt > System.currentTimeMillis()) {
            return entry.addresses.clone();
        }

        IOException last = null;

        // HTTPS DNS avoids many ISP/mobile-network restrictions on raw UDP port 53.
        for (int type : new int[]{1, 28}) {
            try {
                InetAddress[] result = queryDoh(name, type);
                if (result.length > 0) {
                    cache.put(name, new Entry(result,
                            System.currentTimeMillis() + CACHE_MS));
                    return result.clone();
                }
            } catch (IOException e) {
                last = e;
            }
        }

        // Fallback for networks where DNS-over-HTTPS is unavailable.
        for (String server : UDP_SERVERS) {
            for (int type : new int[]{1, 28}) {
                try {
                    InetAddress[] result = queryUdp(server, name, type);
                    if (result.length > 0) {
                        cache.put(name, new Entry(result,
                                System.currentTimeMillis() + CACHE_MS));
                        return result.clone();
                    }
                } catch (IOException e) {
                    last = e;
                }
            }
        }

        throw last != null ? last : new IOException("DNS resolution failed: " + name);
    }

    private InetAddress[] queryDoh(String host, int type) throws IOException {
        byte[] query = buildQuery(host, type);
        Socket plain = new Socket();

        try {
            if (!vpn.protect(plain)) {
                throw new IOException("Could not protect DoH socket from VPN loop");
            }

            plain.connect(new InetSocketAddress(
                    InetAddress.getByName(DOH_IP), 443), 5000);
            plain.setSoTimeout(6000);

            SSLSocket tls = (SSLSocket) SSLSocketFactory.getDefault()
                    .createSocket(plain, DOH_HOST, 443, true);

            try (SSLSocket secure = tls) {
                SSLParameters parameters = secure.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                secure.setSSLParameters(parameters);
                secure.setSoTimeout(6000);
                secure.startHandshake();

                OutputStream out = secure.getOutputStream();
                String headers =
                        "POST /dns-query HTTP/1.1\r\n" +
                        "Host: " + DOH_HOST + "\r\n" +
                        "Accept: application/dns-message\r\n" +
                        "Content-Type: application/dns-message\r\n" +
                        "Content-Length: " + query.length + "\r\n" +
                        "Connection: close\r\n\r\n";

                out.write(headers.getBytes(StandardCharsets.US_ASCII));
                out.write(query);
                out.flush();

                HttpBody response = readHttpBody(secure.getInputStream());
                if (response.status < 200 || response.status >= 300) {
                    throw new IOException("DoH HTTP status " + response.status);
                }

                return parseAnswers(response.body, response.body.length, type);
            }
        } finally {
            try { plain.close(); } catch (Exception ignored) {}
        }
    }

    private InetAddress[] queryUdp(String server, String host, int type)
            throws IOException {
        byte[] query = buildQuery(host, type);
        DatagramSocket socket = new DatagramSocket();

        try {
            if (!vpn.protect(socket)) {
                throw new IOException("Could not protect DNS socket");
            }

            socket.setSoTimeout(1500);
            DatagramPacket request = new DatagramPacket(
                    query, query.length, InetAddress.getByName(server), 53);
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

        for (String label : host.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            if (bytes.length == 0 || bytes.length > 63) {
                throw new IOException("Invalid DNS label");
            }
            out.write(bytes.length);
            out.write(bytes);
        }

        out.write(0);
        out.write((type >>> 8) & 255);
        out.write(type & 255);
        out.write(0x00); out.write(0x01); // IN
        return out.toByteArray();
    }

    private HttpBody readHttpBody(InputStream in) throws IOException {
        String statusLine = readAsciiLine(in);
        if (statusLine == null || !statusLine.startsWith("HTTP/")) {
            throw new IOException("Invalid DoH HTTP response");
        }

        String[] statusParts = statusLine.split(" ");
        if (statusParts.length < 2) {
            throw new IOException("Invalid DoH HTTP status line");
        }

        int status;
        try {
            status = Integer.parseInt(statusParts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid DoH HTTP status", e);
        }

        int contentLength = -1;
        boolean chunked = false;

        String line;
        while ((line = readAsciiLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;

            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();

            if (name.equals("content-length")) {
                try { contentLength = Integer.parseInt(value); }
                catch (NumberFormatException ignored) {}
            } else if (name.equals("transfer-encoding") &&
                    value.toLowerCase(Locale.US).contains("chunked")) {
                chunked = true;
            }
        }

        byte[] body;
        if (chunked) {
            body = readChunked(in);
        } else if (contentLength >= 0) {
            body = readExactly(in, contentLength);
        } else {
            body = readToEnd(in);
        }

        return new HttpBody(status, body);
    }

    private byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readAsciiLine(in);
            if (line == null) throw new EOFException("Truncated chunked response");

            int semicolon = line.indexOf(';');
            if (semicolon >= 0) line = line.substring(0, semicolon);

            int size;
            try { size = Integer.parseInt(line.trim(), 16); }
            catch (NumberFormatException e) {
                throw new IOException("Invalid chunk size", e);
            }

            if (size == 0) {
                while ((line = readAsciiLine(in)) != null && !line.isEmpty()) {}
                break;
            }

            body.write(readExactly(in, size));
            readAsciiLine(in); // CRLF following this chunk
        }
        return body.toByteArray();
    }

    private String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        boolean sawAny = false;

        while ((c = in.read()) != -1) {
            sawAny = true;
            if (c == '\n') break;
            if (c != '\r') line.write(c);
        }

        if (!sawAny && line.size() == 0) return null;
        return line.toString("US-ASCII");
    }

    private byte[] readExactly(InputStream in, int length) throws IOException {
        if (length < 0 || length > 65536) {
            throw new IOException("Invalid HTTP body length");
        }

        byte[] data = new byte[length];
        int offset = 0;

        while (offset < length) {
            int n = in.read(data, offset, length - offset);
            if (n < 0) throw new EOFException("Truncated HTTP body");
            offset += n;
        }
        return data;
    }

    private byte[] readToEnd(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int count;

        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
            if (out.size() > 65536) throw new IOException("DoH response too large");
        }
        return out.toByteArray();
    }

    private InetAddress[] parseAnswers(byte[] data, int len, int wantedType)
            throws IOException {
        if (len < 12) return new InetAddress[0];

        int responseFlags = u16(data, 2);
        if ((responseFlags & 0x8000) == 0) {
            throw new IOException("DNS packet is not a response");
        }
        if ((responseFlags & 0x000F) != 0) {
            return new InetAddress[0];
        }

        int questionCount = u16(data, 4);
        int answerCount = u16(data, 6);
        int offset = 12;

        for (int i = 0; i < questionCount; i++) {
            offset = skipName(data, len, offset);
            if (offset < 0 || offset + 4 > len) return new InetAddress[0];
            offset += 4;
        }

        List<InetAddress> result = new ArrayList<>();
        for (int i = 0; i < answerCount && offset >= 0 && offset + 10 <= len; i++) {
            offset = skipName(data, len, offset);
            if (offset < 0 || offset + 10 > len) break;

            int type = u16(data, offset); offset += 2;
            int clazz = u16(data, offset); offset += 2;
            offset += 4; // TTL
            int dataLength = u16(data, offset); offset += 2;
            if (offset + dataLength > len) break;

            if (clazz == 1 && type == wantedType &&
                    ((wantedType == 1 && dataLength == 4) ||
                     (wantedType == 28 && dataLength == 16))) {
                result.add(InetAddress.getByAddress(
                        Arrays.copyOfRange(data, offset, offset + dataLength)));
            }

            offset += dataLength;
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

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
    }

    private static String normalize(String host) {
        String result = host == null ? "" : host.trim().toLowerCase(Locale.US);
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static final class Entry {
        final InetAddress[] addresses;
        final long expiresAt;

        Entry(InetAddress[] addresses, long expiresAt) {
            this.addresses = addresses;
            this.expiresAt = expiresAt;
        }
    }

    private static final class HttpBody {
        final int status;
        final byte[] body;

        HttpBody(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }
    }
}
