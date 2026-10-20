package io.github.scf37.river;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfigFileTest {
  private int savedN;

  @BeforeEach
  void lowerCost() {
    savedN = ConfigFile.scryptN;
    ConfigFile.scryptN = 1 << 8;
  }

  @AfterEach
  void restoreCost() {
    ConfigFile.scryptN = savedN;
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void plainRoundTrip() {
    ConfigFile c = new ConfigFile(null);
    byte[] file = c.seal("a: 1\nb: 2\n");
    ConfigFile.Sealed s = ConfigFile.wellFormed(file);
    assertNotNull(s);
    assertEquals("", s.salt());
    assertArrayEquals(bytes("a: 1\nb: 2\n"), s.covered());
    assertTrue(c.verify(s));
  }

  @Test
  void keyedRoundTripUsesAFreshSaltEveryTime() {
    ConfigFile c = new ConfigFile("secret");
    ConfigFile.Sealed s1 = ConfigFile.wellFormed(c.seal("a: 1\n"));
    ConfigFile.Sealed s2 = ConfigFile.wellFormed(c.seal("a: 1\n"));
    assertEquals(32, s1.salt().length());
    assertTrue(!s1.salt().equals(s2.salt()) && !s1.hash().equals(s2.hash()));
    assertTrue(c.verify(s1));
    assertTrue(new ConfigFile("secret").verify(s2));
    assertFalse(new ConfigFile("other").verify(s1), "wrong key");
    assertFalse(new ConfigFile(null).verify(s1), "no key, keyed file");
    assertFalse(c.verify(ConfigFile.wellFormed(new ConfigFile(null).seal("a: 1\n"))), "key, plain file: no downgrade");
  }

  @Test
  void tamperedContentFails() {
    ConfigFile c = new ConfigFile("secret");
    byte[] file = c.seal("paths: [/a]\n");
    file[8] = (byte) 'b';
    assertFalse(c.verify(ConfigFile.wellFormed(file)));
    byte[] plain = new ConfigFile(null).seal("paths: [/a]\n");
    plain[8] = (byte) 'b';
    assertFalse(new ConfigFile(null).verify(ConfigFile.wellFormed(plain)));
  }

  @Test
  void everyTruncationIsNotWellFormed() {
    byte[] file = new ConfigFile("secret").seal("a: 1\n");
    for (int n = 0; n < file.length; n++)
      assertNull(ConfigFile.wellFormed(Arrays.copyOf(file, n)), "prefix of " + n + " bytes");
    assertNotNull(ConfigFile.wellFormed(file));
  }

  @Test
  void sealLineMustBeLastAndComplete() {
    assertNull(ConfigFile.wellFormed(bytes("a: 1\nmac: .abc\n")));
    String hash = "0".repeat(64);
    assertNotNull(ConfigFile.wellFormed(bytes("a: 1\nmac: ." + hash + "\n")));
    assertNull(ConfigFile.wellFormed(bytes("a: 1\nmac: ." + hash + "\nmore: 1\n")));
    assertNull(ConfigFile.wellFormed(bytes("a: 1\nmac: ." + hash)));
    assertNull(ConfigFile.wellFormed(bytes("mac: ." + hash + "\n")), "needs a body in front");
  }

  @Test
  void realScryptCostMatchesIndependentComputation() {
    ConfigFile.scryptN = savedN;
    assertEquals(1 << 17, ConfigFile.scryptN);
    byte[] salt = HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f");
    byte[] file = new ConfigFile("pw").seal("a: 1\nb: 2\n", salt);
    // HMAC-SHA256 under scrypt("pw", salt, 131072, 8, 1) computed with python's hashlib
    assertEquals("a: 1\nb: 2\nmac: 000102030405060708090a0b0c0d0e0f."
        + "9aee45365bc303da9c90693a9ca55a59992222dc0b7ac199e4d609b6c210de37\n", new String(file, StandardCharsets.UTF_8));
  }

  @Test
  void names() {
    String f = "20260101T123456789";
    assertEquals(3, Names.parse("config.00003.0a1b2c3d.yaml").revision());
    assertTrue(Names.parse("config.00003.0a1b2c3d.yaml").config());
    assertEquals(12, Names.parse(f + "/arc00012.0a1b2c3d.jpaq").revision());
    assertEquals(f, Names.parse(f + "/arc00012.0a1b2c3d.jpaq").folder());
    assertTrue(Names.parse(f + "/idx00012.ffffffff.jpaq").index());
    for (String bad : new String[] {"config.3.0a1b2c3d.yaml", "config.00003.0A1B2C3D.yaml", "config.00003.0a1b2c3.yaml",
        f + "/arc00012.0a1b2c3d.jpaq.tmp", "arc-000001.jpaq", "arc00012.0a1b2c3d.jpaq", "../arc00012.0a1b2c3d.jpaq",
        "x/arc00012.0a1b2c3d.jpaq", "g" + f + "/arc00012.0a1b2c3d.jpaq", f + "/x/arc00012.0a1b2c3d.jpaq",
        f + "/arc00012.0a1b2c3d.jpaq\n", f + "//arc00012.0a1b2c3d.jpaq", f + "/config.00003.0a1b2c3d.yaml",
        "river.conf", "notes.txt", "config.99999999999.0a1b2c3d.yaml"})
      assertNull(Names.parse(bad), bad);
    assertNotNull(Names.parse(Names.config(7)));
    assertNotNull(Names.parse(Names.tome(f, 7)));
    assertNotNull(Names.parse(Names.index(f, 7)));
  }
}
