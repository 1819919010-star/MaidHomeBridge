package JumDa5he.maidhomebridge.client;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Local client preference, never synchronized to a game server. */
public record EndpointSettings(String host,int port) {
    public static final EndpointSettings DEFAULT=new EndpointSettings("127.0.0.1",7411);
    public EndpointSettings {
        host=host.trim();
        if(host.isEmpty()||host.length()>253||host.contains("/")||host.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("请填写手机的 IP 地址，不要带网址前缀或空格");
        if(port<1||port>65535)throw new IllegalArgumentException("端口应为 1～65535，通常填写 7411");
    }
    public static EndpointSettings load(Path file)throws IOException {
        if(!Files.exists(file))return DEFAULT;
        Properties p=new Properties();try(var input=Files.newInputStream(file)){p.load(input);}
        try{return new EndpointSettings(p.getProperty("host","127.0.0.1"),Integer.parseInt(p.getProperty("port","7411")));}
        catch(IllegalArgumentException e){throw new IOException("连接设置无效",e);}
    }
    public void save(Path file)throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Properties p=new Properties();p.setProperty("host",host);p.setProperty("port",Integer.toString(port));
        Path temporary=Files.createTempFile(file.toAbsolutePath().getParent(),"maidhome-endpoint-",".tmp");
        try {
            try(var output=Files.newOutputStream(temporary)){p.store(output,"MaidHome Bridge client endpoint");}
            try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
}
