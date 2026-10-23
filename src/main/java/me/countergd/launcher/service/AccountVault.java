package me.countergd.launcher.service;

import com.google.gson.Gson;
import me.countergd.launcher.model.ElyByAccount;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Passphrase-encrypted local account file. The passphrase is never persisted. */
public final class AccountVault {
    private static final String MAGIC="CLV1";
    private final Path file;
    private final Gson gson=new Gson();
    public AccountVault(Path file) { this.file=file; }
    public boolean exists() { return Files.isRegularFile(file); }
    public void save(ElyByAccount account,char[] passphrase) throws Exception {
        if(passphrase==null||passphrase.length<10) throw new IOException("Choose a vault passphrase of at least 10 characters.");
        byte[] salt=new byte[16],nonce=new byte[12]; SecureRandom random=new SecureRandom(); random.nextBytes(salt); random.nextBytes(nonce);
        byte[] plain=gson.toJson(account).getBytes(StandardCharsets.UTF_8);
        byte[] encrypted;
        try { Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,derive(passphrase,salt),new GCMParameterSpec(128,nonce)); cipher.updateAAD(MAGIC.getBytes(StandardCharsets.US_ASCII)); encrypted=cipher.doFinal(plain); }
        finally { Arrays.fill(plain,(byte)0); Arrays.fill(passphrase,'\0'); }
        byte[] all=new byte[MAGIC.length()+salt.length+nonce.length+encrypted.length]; int p=0;
        System.arraycopy(MAGIC.getBytes(StandardCharsets.US_ASCII),0,all,p,MAGIC.length());p+=MAGIC.length();
        System.arraycopy(salt,0,all,p,salt.length);p+=salt.length;System.arraycopy(nonce,0,all,p,nonce.length);p+=nonce.length;System.arraycopy(encrypted,0,all,p,encrypted.length);
        Files.createDirectories(file.getParent()); Path tmp=file.resolveSibling(file.getFileName()+".tmp"); Files.write(tmp,all);
        try { Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
        setPrivatePermissions(file);
    }
    public ElyByAccount load(char[] passphrase) throws Exception {
        byte[] all=Files.readAllBytes(file); if(all.length<4+16+12+16||!MAGIC.equals(new String(all,0,4,StandardCharsets.US_ASCII))) throw new IOException("Saved account vault is invalid or unsupported.");
        byte[] salt=Arrays.copyOfRange(all,4,20),nonce=Arrays.copyOfRange(all,20,32),ciphertext=Arrays.copyOfRange(all,32,all.length);
        try { Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,derive(passphrase,salt),new GCMParameterSpec(128,nonce));cipher.updateAAD(MAGIC.getBytes(StandardCharsets.US_ASCII));
            byte[] plain=cipher.doFinal(ciphertext); try { return gson.fromJson(new String(plain,StandardCharsets.UTF_8),ElyByAccount.class); } finally { Arrays.fill(plain,(byte)0); }
        } catch(AEADBadTagException e){throw new IOException("Incorrect vault passphrase or damaged account vault.");}
        finally { Arrays.fill(passphrase,'\0'); Arrays.fill(all,(byte)0); }
    }
    public void delete() throws IOException { Files.deleteIfExists(file); }
    private static SecretKey derive(char[] password,byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec=new PBEKeySpec(password,salt,310_000,256); try { byte[] key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); try{return new SecretKeySpec(key,"AES");} finally{Arrays.fill(key,(byte)0);} } finally{spec.clearPassword();}
    }
    private static void setPrivatePermissions(Path file) {
        try { Files.setPosixFilePermissions(file,Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)); }
        catch(Exception ignored) {}
    }
}
