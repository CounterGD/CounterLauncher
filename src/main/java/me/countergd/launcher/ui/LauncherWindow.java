package me.countergd.launcher.ui;

import me.countergd.launcher.model.*;
import me.countergd.launcher.service.*;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class LauncherWindow extends JFrame {
    private final Path root=Paths.get(System.getProperty("user.home"),".counterlauncher");
    private final HttpService http=new HttpService();
    private final VersionService versions=new VersionService(http,root);
    private final MinecraftInstaller installer=new MinecraftInstaller(versions,root);
    private final ElyByOAuthService oauth=new ElyByOAuthService(http);
    private final AccountVault vault=new AccountVault(root.resolve("accounts.enc"));
    private final LaunchService launcher=new LaunchService();
    private final SettingsService settings;
    private final DefaultListModel<GameVersion> versionModel=new DefaultListModel<>();
    private final JList<GameVersion> versionList=new JList<>(versionModel);
    private final JTextArea log=new JTextArea();
    private final JLabel accountLabel=new JLabel("Not signed in");
    private final JTextField clientIdField=new JTextField(20);
    private final JPasswordField clientSecretField=new JPasswordField(20);
    private final JTextField redirectField=new JTextField("http://127.0.0.1:8765/callback",20);
    private final JTextField javaField=new JTextField("java",18);
    private final JSpinner minRam=new JSpinner(new SpinnerNumberModel(512,256,16384,256));
    private final JSpinner maxRam=new JSpinner(new SpinnerNumberModel(2048,512,32768,256));
    private volatile ElyByAccount account;
    private volatile MinecraftInstaller.InstallResult installed;
    private volatile Process gameProcess;

    public LauncherWindow(){
        super("CounterLauncher 0.2.1"); SettingsService loaded;
        try{loaded=new SettingsService(root.resolve("settings.properties"));}catch(Exception e){loaded=null;log("Could not load settings: "+e.getMessage());}
        settings=loaded;loadSettings();
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);setMinimumSize(new Dimension(900,650));setSize(1040,760);setLocationRelativeTo(null);setLayout(new BorderLayout(10,10));
        add(buildTop(),BorderLayout.NORTH);add(buildMain(),BorderLayout.CENTER);add(buildBottom(),BorderLayout.SOUTH);
        log("CounterLauncher 0.2.1 started. Data: "+root);
        log("Installer downloads version libraries, native archives and asset objects with hash verification.");
        log("OAuth uses Ely.by browser authorization; account tokens are stored in a passphrase-encrypted vault.");
        refreshVersions();
    }
    private JPanel buildTop(){JPanel p=new JPanel(new BorderLayout());p.setBorder(BorderFactory.createEmptyBorder(10,10,0,10));JLabel title=new JLabel("CounterLauncher");title.setFont(title.getFont().deriveFont(Font.BOLD,24f));p.add(title,BorderLayout.WEST);p.add(accountLabel,BorderLayout.EAST);return p;}
    private JComponent buildMain(){
        JPanel outer=new JPanel(new GridLayout(1,2,10,0));outer.setBorder(BorderFactory.createEmptyBorder(0,10,0,10));
        JPanel vp=new JPanel(new BorderLayout(5,5));vp.setBorder(BorderFactory.createTitledBorder("Minecraft versions"));versionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);vp.add(new JScrollPane(versionList),BorderLayout.CENTER);
        JPanel buttons=new JPanel(new GridLayout(2,2,5,5));JButton refresh=new JButton("Refresh versions");refresh.addActionListener(e->refreshVersions());JButton install=new JButton("Install full version");install.addActionListener(e->installSelected());JButton launch=new JButton("Launch installed version");launch.addActionListener(e->launchSelected());JButton stop=new JButton("Stop game");stop.addActionListener(e->stopGame());buttons.add(refresh);buttons.add(install);buttons.add(launch);buttons.add(stop);vp.add(buttons,BorderLayout.SOUTH);
        JPanel right=new JPanel();right.setLayout(new BoxLayout(right,BoxLayout.Y_AXIS));
        JPanel authPanel=new JPanel(new GridBagLayout());authPanel.setBorder(BorderFactory.createTitledBorder("Ely.by OAuth 2.0"));GridBagConstraints c=new GridBagConstraints();c.insets=new Insets(3,4,3,4);c.fill=GridBagConstraints.HORIZONTAL;c.weightx=1;
        c.gridx=0;c.gridy=0;c.weightx=0;authPanel.add(new JLabel("OAuth client ID"),c);c.gridx=1;c.weightx=1;authPanel.add(clientIdField,c);
        c.gridx=0;c.gridy=1;c.weightx=0;authPanel.add(new JLabel("Client secret"),c);c.gridx=1;c.weightx=1;authPanel.add(clientSecretField,c);
        c.gridx=0;c.gridy=2;c.weightx=0;authPanel.add(new JLabel("Callback URI"),c);c.gridx=1;c.weightx=1;authPanel.add(redirectField,c);
        JPanel ab=new JPanel(new GridLayout(3,2,5,5));
        JButton signIn=new JButton("Sign in in browser"); signIn.addActionListener(e->oauthLogin());
        JButton load=new JButton("Load saved account"); load.addActionListener(e->loadAccount());
        JButton save=new JButton("Save current account"); save.addActionListener(e->saveAccount());
        JButton forget=new JButton("Forget saved account"); forget.addActionListener(e->forgetAccount());
        JButton refreshToken=new JButton("Refresh OAuth token"); refreshToken.addActionListener(e->refreshOAuthToken());
        JButton signOut=new JButton("Sign out"); signOut.addActionListener(e->{account=null;accountLabel.setText("Not signed in");log("Signed out locally; encrypted saved account was not deleted.");});
        ab.add(signIn);ab.add(load);ab.add(save);ab.add(forget);ab.add(refreshToken);ab.add(signOut);
        c.gridx=0;c.gridy=3;c.gridwidth=2;c.weightx=1;authPanel.add(ab,c);
        JLabel note=new JLabel("<html><small>Register the exact callback URI in your Ely.by application. Never share your client secret.</small></html>");
        c.gridy=4;authPanel.add(note,c);right.add(authPanel);
        JPanel config=new JPanel(new GridBagLayout());config.setBorder(BorderFactory.createTitledBorder("Settings"));GridBagConstraints s=new GridBagConstraints();s.insets=new Insets(4,5,4,5);s.fill=GridBagConstraints.HORIZONTAL;s.gridx=0;s.gridy=0;config.add(new JLabel("Java executable"),s);s.gridx=1;config.add(javaField,s);s.gridx=0;s.gridy=1;config.add(new JLabel("Min RAM (MB)"),s);s.gridx=1;config.add(minRam,s);s.gridx=0;s.gridy=2;config.add(new JLabel("Max RAM (MB)"),s);s.gridx=1;config.add(maxRam,s);JButton saveSettings=new JButton("Save settings");saveSettings.addActionListener(e->saveSettings());s.gridx=0;s.gridy=3;s.gridwidth=2;config.add(saveSettings,s);right.add(config);right.add(Box.createVerticalGlue());outer.add(vp);outer.add(right);return outer;
    }
    private JComponent buildBottom(){JPanel p=new JPanel(new BorderLayout(5,5));p.setBorder(BorderFactory.createEmptyBorder(0,10,10,10));log.setEditable(false);log.setLineWrap(true);log.setWrapStyleWord(true);log.setRows(8);p.add(new JLabel("Launcher log"),BorderLayout.NORTH);p.add(new JScrollPane(log),BorderLayout.CENTER);return p;}

    private void refreshVersions(){log("Fetching official Minecraft version manifest...");busy(true);new SwingWorker<List<GameVersion>,Void>(){protected List<GameVersion> doInBackground()throws Exception{return versions.fetchVersions();}protected void done(){busy(false);try{versionModel.clear();for(GameVersion v:get())versionModel.addElement(v);if(!versionModel.isEmpty())versionList.setSelectedIndex(0);log("Loaded "+versionModel.size()+" versions.");}catch(Exception e){showError("Version list failed",e);}}}.execute();}
    private void installSelected(){
        GameVersion v=versionList.getSelectedValue();
        if(v==null){message("Select a Minecraft version first.");return;}
        log("Installing "+v.id()+" (client, libraries, natives, assets)...");busy(true);
        new SwingWorker<MinecraftInstaller.InstallResult,String>(){
            protected MinecraftInstaller.InstallResult doInBackground()throws Exception{return installer.install(v,s->publish(s));}
            protected void process(List<String> chunks){for(String s:chunks)log(s);}
            protected void done(){busy(false);try{
                installed=get();log("Ready to launch: "+v.id());
                JOptionPane.showMessageDialog(LauncherWindow.this,"Install complete for "+v.id()+".\nLibraries: "+installed.libraries().size()+"\nAssets: "+(installed.assetIndexFile()==null?"none":installed.assetIndexFile()));
            }catch(Exception e){showError("Install failed",e);}}
        }.execute();
    }
    private void oauthLogin(){String id=clientIdField.getText().trim(),secret=new String(clientSecretField.getPassword()),redirect=redirectField.getText().trim();if(id.isBlank()||secret.isBlank()){message("Enter your registered Ely.by OAuth client ID and secret.");return;}saveOAuthSettings(id,redirect);clientSecretField.setText("");log("Starting Ely.by OAuth sign-in in your browser...");busy(true);new SwingWorker<ElyByAccount,Void>(){protected ElyByAccount doInBackground()throws Exception{return oauth.login(id,secret,redirect);}protected void done(){busy(false);try{account=get();accountLabel.setText("Signed in: "+account.username());log("OAuth sign-in successful for "+account.username()+". Choose Save current account to encrypt and persist the tokens.");saveAccount();}catch(Exception e){showError("Ely.by OAuth failed",e);}}}.execute();}
    private void refreshOAuthToken(){
        if(account==null){message("Load or sign in to an account first.");return;}
        String secret=new String(clientSecretField.getPassword());
        if(secret.isBlank()){message("Enter your Ely.by OAuth client secret first; it is not saved by the launcher.");return;}
        clientSecretField.setText("");log("Refreshing Ely.by OAuth token...");busy(true);
        new SwingWorker<ElyByAccount,Void>(){
            protected ElyByAccount doInBackground()throws Exception{return oauth.refresh(account,secret);}
            protected void done(){busy(false);try{account=get();accountLabel.setText("Signed in: "+account.username());log("OAuth token refreshed. Save the updated account vault to persist it.");saveAccount();}catch(Exception e){showError("Token refresh failed",e);}}
        }.execute();
    }
    private void saveAccount(){if(account==null){message("Sign in first.");return;}JPasswordField pass=new JPasswordField();JPasswordField confirm=new JPasswordField();Object[] fields={"Create a vault passphrase (10+ characters). It is never saved:",pass,"Confirm passphrase:",confirm};if(JOptionPane.showConfirmDialog(this,fields,"Encrypt account storage",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;char[] a=pass.getPassword(),b=confirm.getPassword();if(!java.util.Arrays.equals(a,b)){java.util.Arrays.fill(a,'\0');java.util.Arrays.fill(b,'\0');message("Passphrases do not match.");return;}try{vault.save(account,a);java.util.Arrays.fill(b,'\0');log("Account saved encrypted at "+root.resolve("accounts.enc"));}catch(Exception e){showError("Could not save account",e);}}
    private void loadAccount(){if(!vault.exists()){message("No saved account vault exists yet.");return;}JPasswordField pass=new JPasswordField();if(JOptionPane.showConfirmDialog(this,pass,"Unlock saved account",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;try{account=vault.load(pass.getPassword());accountLabel.setText("Signed in: "+account.username());log("Encrypted account vault unlocked.");}catch(Exception e){showError("Could not unlock account",e);}}
    private void forgetAccount(){if(!vault.exists()){message("No saved account to forget.");return;}if(JOptionPane.showConfirmDialog(this,"Delete the encrypted saved account? This cannot be undone.","Forget account",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION)try{vault.delete();account=null;accountLabel.setText("Not signed in");log("Saved account vault deleted.");}catch(Exception e){showError("Could not delete account",e);}}
    private void launchSelected(){GameVersion selected=versionList.getSelectedValue();if(selected==null){message("Select a version.");return;}if(account==null){message("Sign in to Ely.by first.");return;}log("Preparing launch for "+selected.id()+"...");busy(true);new SwingWorker<Process,Void>(){protected Process doInBackground()throws Exception{MinecraftInstaller.InstallResult ready=installed;if(ready==null||!ready.version().id().equals(selected.id()))ready=installer.install(selected,LauncherWindow.this::log);installed=ready;return launcher.launch(javaField.getText().trim().isBlank()?"java":javaField.getText().trim(),ready,account,(Integer)minRam.getValue(),(Integer)maxRam.getValue(),"0.2.0");}protected void done(){busy(false);try{gameProcess=get();log("Minecraft process started (PID may be unavailable on this Java runtime).");}catch(Exception e){showError("Launch failed",e);}}}.execute();}
    private void stopGame(){Process p=gameProcess;if(p!=null&&p.isAlive()){p.destroy();log("Sent stop signal to game process.");}else log("No running game process tracked.");}
    private void loadSettings(){if(settings==null)return;javaField.setText(settings.get("javaExecutable","java"));clientIdField.setText(settings.get("elyClientId",""));redirectField.setText(settings.get("elyRedirectUri","http://127.0.0.1:8765/callback"));try{minRam.setValue(Integer.parseInt(settings.get("minRamMb","512")));}catch(Exception ignored){}try{maxRam.setValue(Integer.parseInt(settings.get("maxRamMb","2048")));}catch(Exception ignored){}}
    private void saveSettings(){if(settings==null){log("Settings unavailable.");return;}int min=(Integer)minRam.getValue(),max=(Integer)maxRam.getValue();if(min>max){message("Minimum RAM cannot exceed maximum RAM.");return;}settings.set("javaExecutable",javaField.getText().trim().isBlank()?"java":javaField.getText().trim());settings.set("minRamMb",Integer.toString(min));settings.set("maxRamMb",Integer.toString(max));settings.set("elyClientId",clientIdField.getText().trim());settings.set("elyRedirectUri",redirectField.getText().trim());try{settings.save();log("Settings saved with owner-only permissions where supported.");}catch(Exception e){showError("Settings save failed",e);}}
    private void saveOAuthSettings(String id,String redirect){if(settings==null)return;settings.set("elyClientId",id);settings.set("elyRedirectUri",redirect);try{settings.save();}catch(Exception e){log("Could not save OAuth settings: "+e.getMessage());}}
    private void busy(boolean value){setCursor(Cursor.getPredefinedCursor(value?Cursor.WAIT_CURSOR:Cursor.DEFAULT_CURSOR));}
    private void log(String msg){SwingUtilities.invokeLater(()->log.append("["+java.time.LocalTime.now().withNano(0)+"] "+msg+"\n"));}
    private void message(String msg){JOptionPane.showMessageDialog(this,msg);}
    private void showError(String title,Throwable t){Throwable x=t;while((x instanceof ExecutionException||x instanceof java.util.concurrent.CompletionException)&&x.getCause()!=null)x=x.getCause();String m=x.getMessage()==null?x.getClass().getSimpleName():x.getMessage();log(title+": "+m);JOptionPane.showMessageDialog(this,m,title,JOptionPane.ERROR_MESSAGE);}
}
