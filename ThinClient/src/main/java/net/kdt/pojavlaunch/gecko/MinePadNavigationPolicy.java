package net.kdt.pojavlaunch.gecko;

import java.io.*;
import java.net.IDN;
import java.net.URI;
import java.net.URLDecoder;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local domain snapshot plus original MinePad keywords and search/redirect checks. */
final class MinePadNavigationPolicy {
    static final String BLOCKED_PAGE = "mod://webdisplays/blacklisted.html";
    private static final String[] LEGACY = {"xvideos.com", "pornhub.com", "xnxx.com", "youporn.com", "redtube.com", "yourporn.com"};
    private static final String[] TERMS = {"xvideos", "pornhub", "xnxx", "youporn", "redtube", "hentai", "gostosa", "novinha", "novinha", "putinha", "sentando", "rebolando", "mamando", "gozada", "chupar", "cu", "cuzinho", "bunda", "pau", "gostosa", "fudeu", "puta", "safada", "transando", "arrombado", "anal", "virgem", "squirting", "pelada", "peladinha", "esperma", "gozo", "dotado", "MILF", "bundão", "vagina", "creampies", "boquete", "tesão", "sexual", "lingerie", "orgasmo", "peitão", "peitões", "fodida", "penetração", "swing", "ménage", "trois", "seios", "bucetinha", "piroca", "pênis", "rola", "pinto", "buceta", "punheta", "bronha", "punhetinha", "siririca", "bichinha", "cock", "mamada", "porn", "porno", "pornografia", "pornography", "pornographic", "pornografico", "xxx", "nsfw", "sexo", "sex", "erotico", "erotica", "erotic", "nude", "nudes", "nudez", "naked", "nudes vazados", "conteudo adulto", "adult content", "sex tape", "sextape", "rule34", "rule 34", "r34", "onlyfans", "fansly", "xhamster", "chaturbate", "stripchat", "spankbang", "eporner", "xvideos", "pornhub", "xnxx", "youporn", "redtube"};
    private static final Pattern MARKS=Pattern.compile("\\p{M}+");
    private static final Pattern WORDS=keywordPattern();
    private static final Pattern DOMAIN=Pattern.compile("(?i)(?:[\\p{L}\\p{N}-]+\\.)+[\\p{L}\\p{N}-]{2,63}");
    private static final Pattern SEARCH_KEY=Pattern.compile("(?i)(?:q|p|text|query|search|search_query|search_term|search_terms|searchterm|keyword|keywords|wd|word|k)");
    private static volatile MinePadDomainList domains;

    static synchronized void load(InputStream in) throws IOException { domains=MinePadDomainList.read(in); }
    static boolean ready(){return domains!=null;}
    static int domainCount(){return domains==null?LEGACY.length:domains.size();}
    static int memoryBytes(){return domains==null?0:domains.bytes();}
    static String filter(String url) {return blocked(url)?BLOCKED_PAGE:url;}

    static boolean blocked(String input) {
        if(input==null||input.trim().isEmpty())return false;
        if(input.length()>8192)return true;
        String url=input.trim(),lower=url.toLowerCase(Locale.ROOT);
        if(lower.startsWith("mod://")||lower.startsWith("webdisplays://")||lower.startsWith("about:")
                ||lower.startsWith("data:")||lower.startsWith("file:"))return false;
        // Check each decoding level: nested redirect links must not hide their final host.
        for(int depth=0;depth<4;depth++) {
            if(blocksHost(hostOf(url))||containsDomain(url)||blockedSearch(url))return true;
            String decoded=decode(url);
            if(decoded.equals(url))break;
            url=decoded;
        }
        return false;
    }
    private static boolean blockedSearch(String url) {
        // Also handles SPA fragment queries and engines beyond the original five.
        String[] parts=url.split("[?&#;]");
        for(int i=1;i<parts.length;i++) {
            int equals=parts[i].indexOf('=');if(equals<0)continue;
            String key=decode(parts[i].substring(0,equals));
            if(!SEARCH_KEY.matcher(key).matches())continue;
            String value=parts[i].substring(equals+1);
            for(int depth=0;depth<4;depth++) {
                if(WORDS.matcher(fold(value)).find()||containsDomain(value))return true;
                String decoded=decode(value);if(decoded.equals(value))break;value=decoded;
            }
        }
        return false;
    }
    private static Pattern keywordPattern() {
        StringBuilder pattern=new StringBuilder("(?<![\\p{L}\\p{N}])(?:");
        for(int i=0;i<TERMS.length;i++){if(i>0)pattern.append('|');pattern.append(Pattern.quote(fold(TERMS[i])));}
        return Pattern.compile(pattern.append(")(?![\\p{L}\\p{N}])").toString());
    }
    private static String fold(String text) {
        return MARKS.matcher(Normalizer.normalize(text,Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
    }
    private static String decode(String value) {
        try{return URLDecoder.decode(value,"UTF-8");}catch(IllegalArgumentException|UnsupportedEncodingException ignored){return value;}
    }
    private static String hostOf(String url) {
        try {
            URI uri=new URI(url.contains("://")?url:"https://"+url);
            String host=uri.getHost();if(host!=null)return host;
            String authority=uri.getRawAuthority();if(authority==null)return "";
            authority=authority.substring(authority.lastIndexOf('@')+1);
            if(authority.startsWith("["))return ""; // IPv6 has no domain-list entry.
            int port=authority.lastIndexOf(':');return port<0?authority:authority.substring(0,port);
        }catch(Exception ignored){return "";}
    }
    private static String normalizeHost(String host) {
        host=host.toLowerCase(Locale.ROOT).replace('\u3002','.').replace('\uff0e','.').replace('\uff61','.');
        while(host.endsWith("."))host=host.substring(0,host.length()-1);
        boolean ascii=true;for(int i=0;i<host.length();i++)if(host.charAt(i)>127){ascii=false;break;}
        if(!ascii)try{host=IDN.toASCII(host,IDN.USE_STD3_ASCII_RULES);}catch(IllegalArgumentException ignored){return "";}
        return host;
    }
    private static boolean blocksHost(String candidate) {
        String host=normalizeHost(candidate);
        for(String denied:LEGACY)if(host.equals(denied)||host.endsWith("."+denied))return true;
        MinePadDomainList list=domains;return list!=null&&list.blocks(host);
    }
    private static boolean containsDomain(String text) {
        Matcher matcher=DOMAIN.matcher(text);
        while(matcher.find())if(blocksHost(matcher.group()))return true;
        return false;
    }
}
