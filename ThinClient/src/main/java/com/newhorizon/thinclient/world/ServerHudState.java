package com.newhorizon.thinclient.world;

import com.newhorizon.thinclient.protocol.*;
import java.nio.ByteBuffer;
import java.util.*;
import static com.newhorizon.thinclient.world.PlayerListState.*;

/** Bounded vanilla boss bars, titles, teams and scoreboard objectives. */
public final class ServerHudState {
    private final LinkedHashMap<UUID,Boss> bosses=new LinkedHashMap<>();
    private final LinkedHashMap<String,Objective> objectives=new LinkedHashMap<>();
    private final LinkedHashMap<String,Team> teams=new LinkedHashMap<>();
    private final LinkedHashMap<String,Score> scores=new LinkedHashMap<>();
    private final String[] display=new String[19];
    private String title="\"\"",subtitle="\"\"";
    private int fadeIn=10,stay=70,fadeOut=20;
    private long titleAt;
    private static final class Boss {String name="\"\"";float progress;int color,overlay,flags;Boss(){}Boss(Boss b){name=b.name;progress=b.progress;color=b.color;overlay=b.overlay;flags=b.flags;}}
    private static final class Objective {String title;int kind;Objective(String title,int kind){this.title=title;this.kind=kind;}}
    private static final class Score {String owner,objective;int value;Score(String owner,String objective,int value){this.owner=owner;this.objective=objective;this.value=value;}}
    private static final class Team {String prefix="\"\"",suffix="\"\"";int color=-1;Set<String> members=new HashSet<>();Team(){}Team(Team t){prefix=t.prefix;suffix=t.suffix;color=t.color;members.addAll(t.members);}}
    public static boolean handles(int id){return id==0x0b||id==0x0e||id==0x51||id==0x58||id==0x5a||id==0x5b||id==0x5d||id==0x5f||id==0x60;}
    public synchronized void read(int id,ByteBuffer in)throws ProtocolException{
        if(id==0x0b){readBoss(in);return;}
        if(id==0x0e){boolean reset=bool(in);end(in);titleAt=0;title=subtitle="\"\"";if(reset){fadeIn=10;stay=70;fadeOut=20;}return;}
        if(id==0x5f||id==0x5d){String text=component(in);end(in);if(id==0x5f){title=text;titleAt=System.nanoTime();}else subtitle=text;return;}
        if(id==0x60){BinaryCodec.require(in,12);int a=in.getInt(),b=in.getInt(),c=in.getInt();end(in);if(a>=0)fadeIn=Math.min(12000,a);if(b>=0)stay=Math.min(12000,b);if(c>=0)fadeOut=Math.min(12000,c);return;}
        if(id==0x51){BinaryCodec.require(in,1);int slot=in.get()&255;String name=BinaryCodec.readString(in,128);end(in);if(slot<display.length)display[slot]=name;return;}
        if(id==0x58){String name=BinaryCodec.readString(in,128);BinaryCodec.require(in,1);int action=in.get()&255;String text="";int kind=0;
            if(action==0||action==2){text=component(in);kind=count(in,1);}else if(action!=1)throw new ProtocolException("Objective action");end(in);
            if(action==1){objectives.remove(name);scores.values().removeIf(s->s.objective.equals(name));for(int i=0;i<display.length;i++)if(name.equals(display[i]))display[i]=null;}
            else if(action==2?objectives.containsKey(name):objectives.size()<32)objectives.put(name,new Objective(text,kind));return;
        }
        if(id==0x5b){String owner=BinaryCodec.readString(in,160);int action=count(in,1);String objective=BinaryCodec.readString(in,128);int value=action==0?VarInts.read(in):0;end(in);
            String key=objective+'\0'+owner;
            if(action==1){if(objective.isEmpty())scores.values().removeIf(s->s.owner.equals(owner));else scores.remove(key);}
            else if(scores.size()<1024||scores.containsKey(key))scores.put(key,new Score(owner,objective,value));return;
        }
        if(id==0x5a)readTeam(in);
    }
    private void readBoss(ByteBuffer in)throws ProtocolException{
        UUID id=BinaryCodec.readUuid(in);int action=count(in,5);Boss old=bosses.get(id),b=old==null?new Boss():new Boss(old);
        if(action==0||action==3)b.name=component(in);
        if(action==0||action==2){BinaryCodec.require(in,4);float value=in.getFloat();if(!Float.isFinite(value))throw new ProtocolException("Boss progress");b.progress=Math.max(0,Math.min(1,value));}
        if(action==0||action==4){b.color=count(in,6);b.overlay=count(in,4);}
        if(action==0||action==5){BinaryCodec.require(in,1);b.flags=in.get()&255;}end(in);
        if(action==1)bosses.remove(id);else if(old!=null||action==0&&bosses.size()<16)bosses.put(id,b);
    }
    private void readTeam(ByteBuffer in)throws ProtocolException{
        String name=BinaryCodec.readString(in,128);BinaryCodec.require(in,1);int action=in.get()&255;if(action>4)throw new ProtocolException("Team action");
        Team old=teams.get(name),t=old==null?new Team():new Team(old);
        if(action==0||action==2){component(in);BinaryCodec.require(in,1);in.get();BinaryCodec.readString(in,160);BinaryCodec.readString(in,160);t.color=VarInts.read(in);t.prefix=component(in);t.suffix=component(in);}
        List<String> members=new ArrayList<>();if(action==0||action==3||action==4){int n=count(in,4096);for(int i=0;i<n;i++){String member=BinaryCodec.readString(in,160);if(i<256)members.add(member);}}end(in);
        if(action==1){teams.remove(name);return;}
        if(old==null&&action!=0||old==null&&teams.size()>=64)return;
        if(action==0)t.members.clear();
        if(action==4)t.members.removeAll(members);else if(action==0||action==3)for(String member:members){for(Team other:teams.values())other.members.remove(member);if(t.members.size()<256)t.members.add(member);}
        teams.put(name,t);
    }
    private static String component(ByteBuffer in)throws ProtocolException{return BinaryCodec.readString(in,32768);}
    private String teamOf(String name){for(Map.Entry<String,Team> e:teams.entrySet())if(e.getValue().members.contains(name))return e.getKey();return "";}
    private String name(String player){Team t=teams.get(teamOf(player));if(t==null)return ChatProtocol.quote(player);
        String base="{\"text\":"+ChatProtocol.quote(player)+(t.color>=0&&t.color<16?",\"color\":\""+COLORS[t.color]+"\"":"")+"}";
        return "["+t.prefix+","+base+","+t.suffix+"]";
    }
    private static final String[] COLORS={"black","dark_blue","dark_green","dark_aqua","dark_red","dark_purple","gold","gray","dark_gray","blue","green","aqua","red","light_purple","yellow","white"};
    public synchronized String encode(PlayerListState players,boolean tab,long now){
        StringBuilder out=new StringBuilder(2048);out.append("{\"local\":").append(ChatProtocol.quote(String.valueOf(players.localId())));
        out.append(",\"header\":").append(ChatProtocol.quote(players.header())).append(",\"footer\":").append(ChatProtocol.quote(players.footer()));
        out.append(",\"players\":[");
        if(tab){List<PlayerListState.Entry> list=players.listed();list.sort(Comparator.comparingInt((PlayerListState.Entry e)->e.gameMode==3?1:0).thenComparing(e->teamOf(e.name)).thenComparing(e->e.name,String.CASE_INSENSITIVE_ORDER));
            for(int i=0;i<Math.min(80,list.size());i++){PlayerListState.Entry e=list.get(i);if(i>0)out.append(',');
                String text=e.display.isEmpty()?name(e.name):e.display;Score score=scores.get(display[0]+'\0'+e.name);
                out.append("{\"id\":").append(ChatProtocol.quote(e.id.toString())).append(",\"name\":").append(ChatProtocol.quote(text)).append(",\"ping\":").append(e.latency).append(",\"spectator\":").append(e.gameMode==3);
                if(score!=null)out.append(",\"score\":").append(score.value);out.append('}');
            }}out.append(']');
        out.append(",\"bosses\":[");int i=0;for(Boss b:bosses.values()){if(i++>0)out.append(',');out.append("{\"name\":").append(ChatProtocol.quote(b.name)).append(",\"progress\":").append(b.progress).append(",\"color\":").append(b.color).append(",\"overlay\":").append(b.overlay).append('}');}out.append(']');
        String sidebar=display[1];Team localTeam=teams.get(teamOf(players.localName()));if(localTeam!=null&&localTeam.color>=0&&localTeam.color<16&&display[3+localTeam.color]!=null)sidebar=display[3+localTeam.color];
        Objective objective=objectives.get(sidebar);out.append(",\"sidebarTitle\":").append(ChatProtocol.quote(objective==null?"\"\"":objective.title)).append(",\"scores\":[");
        if(objective!=null){List<Score> rows=new ArrayList<>();for(Score s:scores.values())if(s.objective.equals(sidebar)&&!s.owner.startsWith("#"))rows.add(s);
            rows.sort(Comparator.comparingInt((Score s)->s.value).reversed().thenComparing(s->s.owner,String.CASE_INSENSITIVE_ORDER));
            for(i=0;i<Math.min(15,rows.size());i++){Score s=rows.get(i);if(i>0)out.append(',');out.append("{\"name\":").append(ChatProtocol.quote(name(s.owner))).append(",\"value\":").append(s.value).append('}');}}
        float elapsed=(now-titleAt)/50_000_000f,total=fadeIn+stay+fadeOut;
        float alpha=titleAt==0||elapsed>=total?0:elapsed<fadeIn?elapsed/Math.max(1,fadeIn):elapsed>fadeIn+stay?(total-elapsed)/Math.max(1,fadeOut):1;
        out.append("],\"title\":").append(ChatProtocol.quote(title)).append(",\"subtitle\":").append(ChatProtocol.quote(subtitle)).append(",\"titleAlpha\":").append(Math.max(0,Math.min(1,alpha))).append('}');return out.toString();
    }
}
