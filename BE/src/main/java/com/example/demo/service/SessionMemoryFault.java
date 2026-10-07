package com.example.demo.service;

/** Stable, non-sensitive failure code; never include upstream exception text. */
public class SessionMemoryFault extends RuntimeException {
    public SessionMemoryFault(String code) { super(code); }
}
