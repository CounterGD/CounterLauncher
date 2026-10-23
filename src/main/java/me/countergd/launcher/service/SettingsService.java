package me.countergd.launcher.service;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class SettingsService {
    private final Path file; private final Properties properties=new Properties();
    public SettingsService(Path file) throws IOException {this.file=file;if(Files.exists(file))try(InputStream in=Files.newInputStream(file)){properties.load(in);}}
    public String get(String key,String fallback){return properties.getProperty(key,fallback);}
    public void set(String key,String value){properties.setProperty(key,value);}
    public void save() throws IOException {Files.createDirectories(file.getParent());try(OutputStream out=Files.newOutputStream(file)){properties.store(out,"CounterLauncher settings");}
        try{Files.setPosixFilePermissions(file,Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));}catch(Exception ignored){} }
}
