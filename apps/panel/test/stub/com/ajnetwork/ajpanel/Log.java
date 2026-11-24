package com.ajnetwork.ajpanel;
public class Log {
    public static final java.util.List<String> lines = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    public static void i(String m) { lines.add(m); System.out.println("LOG " + m); }
    public static void e(String m, Throwable t) { i(m + ": " + t); }
}
