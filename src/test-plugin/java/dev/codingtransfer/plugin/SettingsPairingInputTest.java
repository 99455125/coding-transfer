package dev.codingtransfer.plugin;
import javax.swing.JTextField;
import java.io.IOException;
import java.nio.file.*;
import com.intellij.credentialStore.*;
public class SettingsPairingInputTest {
 public static void main(String[] args)throws Exception{
  Path dir=Files.createTempDirectory("settings-pair-input-");try{
   CredentialStore store=new CredentialStore(){public Credentials get(CredentialAttributes a){throw new AssertionError("parsing must not load credentials");}public void set(CredentialAttributes a,Credentials c){throw new AssertionError("parsing must not save credentials");}};
   PairingSecretService service=new PairingSecretService(dir,store);var pair=service.generate();String text=service.exportPairing(pair.identity(),pair.key());JTextField identity=new JTextField();
   var imported=SettingsDialog.importPairingText(service,text,identity);if(!identity.getText().equals(pair.identity())||!java.util.Arrays.equals(imported.key(),pair.key()))throw new AssertionError("manual paste must fill identity without separate entry");
   identity.setText("old_identity");SettingsDialog.importPairingText(service,text,identity);if(!identity.getText().equals(pair.identity()))throw new AssertionError("valid pairing controls imported identity");
   identity.setText("retained");try{SettingsDialog.importPairingText(service,"invalid",identity);throw new AssertionError("invalid pairing accepted");}catch(IOException expected){}if(!identity.getText().equals("retained"))throw new AssertionError("invalid paste changed identity");
   if(SettingsDialog.importPairingText(service,"",identity)!=null||!identity.getText().equals("retained"))throw new AssertionError("blank pairing changed manual identity");System.out.println("PASS manual pairing input");
  }finally{Files.delete(dir);}
 }
}
