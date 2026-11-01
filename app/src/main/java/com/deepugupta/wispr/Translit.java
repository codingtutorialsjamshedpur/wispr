/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline last line of defence for Hinglish mode.
 * If Groq's text model is down / rate-limited / removed, Hindi (Devanagari) and Urdu script are still
 * turned into Roman letters on the phone, so Hinglish mode NEVER types Hindi script.
 * Written in plain Java on purpose (no dependencies, testable anywhere).
 */
public final class Translit {
    private Translit() {}

    private static final Map<Character, String> CONS = new HashMap<>();
    private static final Map<Character, String> VOWEL = new HashMap<>();
    private static final Map<Character, String> MATRA = new HashMap<>();
    private static final Map<Character, String> URDU = new HashMap<>();

    static {
        String[][] c = {
            {"क","k"},{"ख","kh"},{"ग","g"},{"घ","gh"},{"ङ","n"},{"च","ch"},{"छ","chh"},{"ज","j"},{"झ","jh"},{"ञ","n"},
            {"ट","t"},{"ठ","th"},{"ड","d"},{"ढ","dh"},{"ण","n"},{"त","t"},{"थ","th"},{"द","d"},{"ध","dh"},{"न","n"},
            {"प","p"},{"फ","ph"},{"ब","b"},{"भ","bh"},{"म","m"},{"य","y"},{"र","r"},{"ल","l"},{"व","v"},{"श","sh"},
            {"ष","sh"},{"स","s"},{"ह","h"},{"ळ","l"}
        };
        for (String[] p : c) CONS.put(p[0].charAt(0), p[1]);
        // precomposed nukta letters U+0958..U+095F, plus U+0929 / U+0931 / U+0934
        CONS.put('\u0958', "q"); CONS.put('\u0959', "kh"); CONS.put('\u095A', "g"); CONS.put('\u095B', "z");
        CONS.put('\u095C', "d"); CONS.put('\u095D', "rh"); CONS.put('\u095E', "f"); CONS.put('\u095F', "y");
        CONS.put('\u0929', "n"); CONS.put('\u0931', "r"); CONS.put('\u0934', "l");
        String[][] v = {
            {"अ","a"},{"आ","aa"},{"इ","i"},{"ई","ee"},{"उ","u"},{"ऊ","oo"},{"ऋ","ri"},{"ए","e"},{"ऐ","ai"},
            {"ओ","o"},{"औ","au"},{"ऍ","e"},{"ऑ","o"},{"ऎ","e"},{"ऒ","o"}
        };
        for (String[] p : v) VOWEL.put(p[0].charAt(0), p[1]);
        String[][] m = {
            {"ा","aa"},{"ि","i"},{"ी","ee"},{"ु","u"},{"ू","oo"},{"ृ","ri"},{"े","e"},{"ै","ai"},{"ो","o"},{"ौ","au"},
            {"ॅ","e"},{"ॉ","o"},{"ॆ","e"},{"ॊ","o"}
        };
        for (String[] p : m) MATRA.put(p[0].charAt(0), p[1]);
        String[][] u = {
            {"ا","a"},{"آ","aa"},{"ب","b"},{"پ","p"},{"ت","t"},{"ٹ","t"},{"ث","s"},{"ج","j"},{"چ","ch"},{"ح","h"},
            {"خ","kh"},{"د","d"},{"ڈ","d"},{"ذ","z"},{"ر","r"},{"ڑ","r"},{"ز","z"},{"ژ","zh"},{"س","s"},{"ش","sh"},
            {"ص","s"},{"ض","z"},{"ط","t"},{"ظ","z"},{"ع","a"},{"غ","gh"},{"ف","f"},{"ق","q"},{"ک","k"},{"ك","k"},
            {"گ","g"},{"ل","l"},{"م","m"},{"ن","n"},{"ں","n"},{"و","o"},{"ہ","h"},{"ھ","h"},{"ه","h"},{"ء",""},
            {"ی","i"},{"ي","i"},{"ے","e"},{"ۓ","e"},{"ئ","i"},{"ؤ","o"},{"ۃ","h"},{"،",","},{"۔","."},{"؟","?"},{"٪","%"}
        };
        for (String[] p : u) URDU.put(p[0].charAt(0), p[1]);
    }

    private static boolean isDeva(char ch) { return ch >= 0x0900 && ch <= 0x097F; }
    private static boolean isArabic(char ch) { return (ch >= 0x0600 && ch <= 0x06FF) || (ch >= 0x0750 && ch <= 0x077F) || (ch >= 0x08A0 && ch <= 0x08FF); }

    /** True if the text still has Devanagari / Urdu / other Indic script that Hinglish mode must not type. */
    public static boolean hasNonLatinIndic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= 0x0900 && ch <= 0x0DFF) || isArabic(ch)) return true;
        }
        return false;
    }

    public static boolean hasDevanagari(String s) {
        for (int i = 0; i < s.length(); i++) if (isDeva(s.charAt(i))) return true;
        return false;
    }

    public static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); i++) if (isArabic(s.charAt(i))) return true;
        return false;
    }

    /** Devanagari + Urdu script -> Hinglish-style Roman letters. Latin text passes through untouched. */
    public static String toRoman(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        StringBuilder word = new StringBuilder();
        int mode = 0; // 0 = other, 1 = devanagari word, 2 = urdu word
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int m = isDeva(ch) && ch != '।' && ch != '॥' && !(ch >= 0x0966 && ch <= 0x096F) ? 1 : isArabic(ch) && !isArabicPunct(ch) ? 2 : 0;
            if (m != mode && word.length() > 0) { out.append(convertWord(word.toString(), mode)); word.setLength(0); }
            mode = m;
            if (m == 0) {
                if (ch == '।' || ch == '॥') out.append('.');
                else if (isArabicPunct(ch)) out.append(URDU.containsKey(ch) ? URDU.get(ch) : "");
                else if (ch >= 0x0966 && ch <= 0x096F) out.append((char) ('0' + (ch - 0x0966)));
                else if (ch >= 0x06F0 && ch <= 0x06F9) out.append((char) ('0' + (ch - 0x06F0)));
                else if (ch >= 0x0660 && ch <= 0x0669) out.append((char) ('0' + (ch - 0x0660)));
                else if (ch >= 0x0980 && ch <= 0x0DFF) { /* other Indic scripts: drop rather than type them */ }
                else out.append(ch);
            } else word.append(ch);
        }
        if (word.length() > 0) out.append(convertWord(word.toString(), mode));
        return capitalizeSentences(out.toString());
    }

    private static boolean isArabicPunct(char ch) { return ch == '،' || ch == '۔' || ch == '؟' || ch == '٪' || (ch >= 0x06F0 && ch <= 0x06F9) || (ch >= 0x0660 && ch <= 0x0669); }

    private static String convertWord(String w, int mode) {
        if (mode == 2) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < w.length(); i++) {
                String r = URDU.get(w.charAt(i));
                if (r != null) b.append(r);
            }
            return b.toString();
        }
        return deva(w);
    }

    // one akshara: consonant cluster/vowel with a vowel sound (or none)
    private static final class Ak {
        String cons = "";      // consonant letters (may be a cluster like "str")
        String vowel = null;   // explicit vowel, null = inherent schwa, "" = killed by virama
        String tail = "";      // anusvara / chandrabindu / visarga
        boolean independent;   // started with an independent vowel letter
    }

    private static String deva(String w) {
        List<Ak> aks = new ArrayList<>();
        Ak cur = null;
        for (int i = 0; i < w.length(); i++) {
            char ch = w.charAt(i);
            char next = i + 1 < w.length() ? w.charAt(i + 1) : 0;
            if (next == '\u093C' && CONS.containsKey(ch)) {
                // letter + nukta -> precomposed equivalent
                cur = startCons(aks, cur, nuktaGuess(ch));
                i++;
                continue;
            }
            if (CONS.containsKey(ch)) { cur = startCons(aks, cur, CONS.get(ch)); continue; }
            if (VOWEL.containsKey(ch)) {
                String vv = VOWEL.get(ch);
                if (cur != null) {
                    // vowel right after a vowel: "भाई" -> "bhai", "आऊंगा" -> "aaunga", "गए" -> "gaye"
                    if (ch == 'ई' || ch == 'इ') vv = "i";
                    else if (ch == 'ऊ' || ch == 'उ') vv = "u";
                    else if (ch == 'ए') vv = "ye";
                    else if (ch == 'ऐ') vv = "ai";
                    if ((ch == 'ई' || ch == 'इ') && "aa".equals(cur.vowel)) cur.vowel = "a";
                }
                cur = new Ak(); cur.vowel = vv; cur.independent = true; aks.add(cur); continue;
            }
            if (MATRA.containsKey(ch) && cur != null) { cur.vowel = MATRA.get(ch); continue; }
            if (ch == '्' && cur != null) { cur.vowel = ""; continue; }
            if ((ch == 'ं' || ch == 'ँ') && cur != null) { cur.tail = "n"; continue; }
            if (ch == 'ः' && cur != null) { cur.tail = "h"; continue; }
            // anything else (rare signs) is ignored
        }
        // merge virama-killed consonants into the next akshara as a cluster
        List<Ak> merged = new ArrayList<>();
        String carry = "";
        for (Ak a : aks) {
            if (!a.independent && "".equals(a.vowel) && a.tail.isEmpty()) { carry += a.cons; continue; }
            if (!carry.isEmpty()) {
                if (a.independent) { Ak c = new Ak(); c.cons = carry; c.vowel = ""; merged.add(c); }
                else a.cons = carry + a.cons;
                carry = "";
            }
            merged.add(a);
        }
        if (!carry.isEmpty()) { Ak c = new Ak(); c.cons = carry; c.vowel = ""; merged.add(c); }

        int n = merged.size();
        boolean[] dropSchwa = new boolean[n];
        // final schwa is silent in Hindi: "कल" -> "kal", but keep it for one-letter words: "न" -> "na"
        if (n > 1 && merged.get(n - 1).vowel == null && merged.get(n - 1).tail.isEmpty()) dropSchwa[n - 1] = true;
        // medial schwa deletion: V C(a) C V -> V C C V  ("समझना" -> "samajhna", "लड़की" -> "ladki")
        for (int i = n - 2; i >= 1; i--) {
            Ak a = merged.get(i);
            if (a.vowel != null || !a.tail.isEmpty()) continue;
            Ak prev = merged.get(i - 1), nxt = merged.get(i + 1);
            boolean prevVoiced = prev.vowel == null ? !dropSchwa[i - 1] : !prev.vowel.isEmpty();
            boolean nextHasVowel = nxt.vowel != null && !nxt.vowel.isEmpty() || (nxt.vowel == null && !dropSchwa[i + 1]);
            if (prevVoiced && nextHasVowel && !nxt.independent && !dropSchwa[i + 1] && i + 1 < n) {
                if (i + 1 == n - 1 && nxt.vowel == null) continue; // "अमर" style: keep "amar"
                dropSchwa[i] = true;
            }
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            Ak a = merged.get(i);
            b.append(a.cons);
            String v;
            if (a.vowel == null) v = dropSchwa[i] ? "" : "a";
            else v = a.vowel;
            boolean last = i == n - 1;
            // chat-style spelling at word end: "क्या" -> "kya", "थी" -> "thi", "तू" -> "tu"
            if (last || (i == n - 2 && merged.get(n - 1).cons.isEmpty() && merged.get(n - 1).independent == false)) {
                if (v.equals("aa") && !a.cons.isEmpty()) v = "a";
                else if (v.equals("ee") && !a.cons.isEmpty()) v = "i";
                else if (v.equals("oo") && !a.cons.isEmpty()) v = "u";
            }
            if (!a.tail.isEmpty() && v.equals("ee")) v = "i";
            b.append(v);
            if (a.tail.equals("n")) b.append(a.cons.isEmpty() && v.isEmpty() ? "" : "n");
            else b.append(a.tail);
        }
        String r = b.toString();
        // common word fixes
        switch (r) {
            case "main": case "mein": case "men": break;
            case "hain": break;
            default: break;
        }
        r = r.replace("chchh", "chh").replace("kkh", "kh").replace("tth", "tth");
        if (r.equals("maen") || r.equals("maein")) r = "main";
        if (r.equals("naheen") || r.equals("nahin")) r = "nahi";
        return r;
    }

    private static String nuktaGuess(char ch) {
        switch (ch) {
            case 'क': return "q"; case 'ख': return "kh"; case 'ग': return "g"; case 'ज': return "z";
            case 'ड': return "d"; case 'ढ': return "rh"; case 'फ': return "f"; default: return CONS.containsKey(ch) ? CONS.get(ch) : "";
        }
    }

    private static Ak startCons(List<Ak> aks, Ak cur, String r) {
        Ak a = new Ak(); a.cons = r; aks.add(a); return a;
    }

    private static String capitalizeSentences(String s) {
        StringBuilder b = new StringBuilder(s);
        boolean cap = true;
        for (int i = 0; i < b.length(); i++) {
            char ch = b.charAt(i);
            if (cap && Character.isLetterOrDigit(ch)) { if (Character.isLetter(ch)) b.setCharAt(i, Character.toUpperCase(ch)); cap = false; }
            else if (ch == '.' || ch == '?' || ch == '!' || ch == '\n') cap = true;
        }
        return b.toString();
    }
}
