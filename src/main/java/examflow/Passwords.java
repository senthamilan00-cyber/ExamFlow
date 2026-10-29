package examflow;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.bouncycastle.crypto.generators.SCrypt;

/** New accounts use PBKDF2. Old Node scrypt hashes remain valid and upgrade at login. */
final class Passwords {
    private static final SecureRandom RANDOM=new SecureRandom();
    static String token(int bytes) {
        byte[] value=new byte[bytes]; RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }
    private static byte[] derive(String password,byte[] salt,int iterations) throws Exception {
        PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,iterations,256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    static String hash(String password) throws Exception {
        String salt=token(16);
        return "pbkdf2:600000:"+salt+":"+HexFormat.of().formatHex(derive(password,HexFormat.of().parseHex(salt),600000));
    }
    static boolean verify(String password,String stored) throws Exception {
        String[] parts=stored.split(":");
        if (parts.length==4 && parts[0].equals("pbkdf2")) {
            return MessageDigest.isEqual(HexFormat.of().parseHex(parts[3]),derive(password,HexFormat.of().parseHex(parts[2]),Integer.parseInt(parts[1])));
        }
        if(parts.length==2) {
            byte[] candidate=SCrypt.generate(password.getBytes(StandardCharsets.UTF_8),parts[0].getBytes(StandardCharsets.UTF_8),16384,8,1,64);
            return MessageDigest.isEqual(HexFormat.of().parseHex(parts[1]),candidate);
        }
        return false;
    }
}
