package com.abdelrhman.webguard.core;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;

public final class DomainBlocker {
    private static final Set<String> domains = new CopyOnWriteArraySet<>();
    private static final String FILE_NAME = "blocked_domains.txt";

    private static final String[] LIST_URLS = {
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
            "https://raw.githubusercontent.com/blocklistproject/Lists/master/porn.txt"
    };

    private static final String[] KEYWORDS = {
            "porn", "xxx", "xvideo", "xnxx", "redtube", "pornhub", "hentai",
            "rule34", "xhamster", "spankbang", "chaturbate", "brazzers",
            "sexcam", "sexvideo", "sexchat", "adult"
    };

    private DomainBlocker() {
    }

    public static synchronized void load(Context context) {
        domains.clear();

        File file = new File(context.getFilesDir(), FILE_NAME);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                String domain = normalizeDomainLine(line);
                if (!domain.isEmpty()) {
                    domains.add(domain);
                }
            }
        } catch (Exception ignored) {
        }

        if (domains.isEmpty()) {
            domains.addAll(Arrays.asList(
                    "pornhub.com",
                    "xvideos.com",
                    "xnxx.com",
                    "xhamster.com",
                    "redtube.com",
                    "spankbang.com",
                    "brazzers.com",
                    "chaturbate.com",
                    "rule34.xxx",
                    "onlyfans.com"
            ));
        }
    }

    public static boolean isBlocked(Context context, String input) {
        if (domains.isEmpty()) {
            load(context);
        }

        String host = extractHost(input);
        if (host.isEmpty()) {
            return false;
        }

        for (String domain : domains) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }

        for (String keyword : KEYWORDS) {
            if (host.contains(keyword)) {
                return true;
            }
        }

        return false;
    }

    public static String extractHost(String input) {
        if (input == null) return "";

        String value = input.trim().toLowerCase(Locale.US);
        if (value.isEmpty()) return "";

        try {
            if (!value.matches("^[a-z][a-z0-9+.-]*://.*$")) {
                value = "http://" + value;
            }

            URL url = new URL(value);
            String host = url.getHost();
            return host == null
                    ? ""
                    : host.toLowerCase(Locale.US).replaceFirst("^www\\.", "");
        } catch (Exception ignored) {
            String raw = value.replaceFirst("^[a-z][a-z0-9+.-]*://", "");

            int slash = raw.indexOf('/');
            if (slash >= 0) raw = raw.substring(0, slash);

            int question = raw.indexOf('?');
            if (question >= 0) raw = raw.substring(0, question);

            int hash = raw.indexOf('#');
            if (hash >= 0) raw = raw.substring(0, hash);

            return raw.replaceFirst("^www\\.", "").trim();
        }
    }

    public static int size(Context context) {
        if (domains.isEmpty()) {
            load(context);
        }
        return domains.size();
    }

    public static synchronized String update(Context context) throws Exception {
        Set<String> merged = new HashSet<>();

        for (String listUrl : LIST_URLS) {
            HttpURLConnection connection = null;

            try {
                connection = (HttpURLConnection) new URL(listUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "WebGuard/2.0");

                try (InputStream input = connection.getInputStream();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(input, StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        String domain = normalizeDomainLine(line);
                        if (!domain.isEmpty()) {
                            merged.add(domain);
                        }
                    }
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        if (merged.isEmpty()) {
            throw new IOException("لم تصل أي قائمة حظر");
        }

        File file = new File(context.getFilesDir(), FILE_NAME);
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8))) {

            for (String domain : new TreeSet<>(merged)) {
                writer.write(domain);
                writer.newLine();
            }
        }

        domains.clear();
        domains.addAll(merged);

        return "تم تحديث قائمة الحظر: " + domains.size() + " نطاقًا";
    }

    private static String normalizeDomainLine(String line) {
        String s = line == null
                ? ""
                : line.trim().toLowerCase(Locale.US);

        if (s.isEmpty() || s.startsWith("#") || s.startsWith("!")) {
            return "";
        }

        if (s.startsWith("||")) {
            s = s.substring(2);
            int end = s.indexOf('^');
            if (end >= 0) {
                s = s.substring(0, end);
            }
        }

        String[] parts = s.split("\\s+");

        if (parts.length >= 2 &&
                (parts[0].equals("0.0.0.0") ||
                 parts[0].equals("127.0.0.1") ||
                 parts[0].equals("::") ||
                 parts[0].equals("::1"))) {
            s = parts[1];
        } else if (parts.length == 1) {
            s = parts[0];
        }

        s = s.replaceFirst("^\\*?\\.", "");

        if (s.contains("/") || s.contains(":") || s.equals("localhost")) {
            return "";
        }

        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }

        return s.matches("^[a-z0-9](?:[a-z0-9.-]{0,251})[a-z0-9]?$")
                ? s
                : "";
    }
}
