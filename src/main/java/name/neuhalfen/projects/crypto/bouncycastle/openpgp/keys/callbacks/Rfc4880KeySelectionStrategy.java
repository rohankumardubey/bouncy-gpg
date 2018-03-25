package name.neuhalfen.projects.crypto.bouncycastle.openpgp.keys.callbacks;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import name.neuhalfen.projects.crypto.bouncycastle.openpgp.keys.keyrings.KeyringConfig;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyFlags;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureSubpacketVector;

/**
 * This implements the key selection strategy for BouncyGPG .
 *
 * This strategy is tries to implement rfc4880 section-5.2.3.21.
 * *
 * https://tools.ietf.org/html/rfc4880#section-5.2.3.21
 */
public class Rfc4880KeySelectionStrategy implements KeySelectionStrategy {

  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory
      .getLogger(Rfc4880KeySelectionStrategy.class);

  private final Instant dateOfTimestampVerification;

  /**
   * The date used for key expiration date checks as "now".
   *
   * @return dateOfTimestampVerification
   */
  protected Instant getDateOfTimestampVerification() {
    return dateOfTimestampVerification;
  }


  /**
   * @param dateOfTimestampVerification The date used for key expiration date checks as "now".
   */
  public Rfc4880KeySelectionStrategy(final Instant dateOfTimestampVerification) {
    this.dateOfTimestampVerification = dateOfTimestampVerification;
  }

  /**
   * Return all keyrings that ARE valid keys for the given uid.
   *
   * Deriving classes can override this.
   *
   * @param uid the userid as passed by upstream.
   * @param keyringConfig the keyring config
   *
   * @return Set with keyrings, never null.
   */
  protected Set<PGPPublicKeyRing> publicKeyRingsForUid(final PURPOSE purpose, final String uid,
      KeyringConfig keyringConfig)
      throws IOException, PGPException {

    Set<PGPPublicKeyRing> keyringsForUid = new HashSet<>();

    final Iterator<PGPPublicKeyRing> keyRings = keyringConfig.getPublicKeyRings()
        .getKeyRings(uid, true, true);

    while (keyRings.hasNext()) {
      keyringsForUid.add(keyRings.next());
    }

    return keyringsForUid;
  }


  @Override
  public Set<PGPPublicKey> validPublicKeysForVerifyingSignatures(String uid,
      KeyringConfig keyringConfig) throws PGPException, IOException {

    final Set<PGPPublicKeyRing> publicKeyrings = this
        .publicKeyRingsForUid(PURPOSE.FOR_SIGNING, uid, keyringConfig);

    final PGPSecretKeyRingCollection secretKeyRings = keyringConfig.getSecretKeyRings();
    return publicKeyrings.stream()
        .flatMap(keyring -> StreamSupport.stream(keyring.spliterator(), false))
        .filter(this::isVerificationKey)
        .filter(this::isNotRevoked)
        .filter(this::isNotExpired)
        .filter(hasPrivateKey(secretKeyRings))
        .collect(Collectors.toSet());
  }

  @Nullable
  @Override
  public PGPPublicKey selectPublicKey(PURPOSE purpose, String uid, KeyringConfig keyringConfig)
      throws PGPException, IOException {

    final Set<PGPPublicKeyRing> publicKeyrings = this
        .publicKeyRingsForUid(purpose, uid, keyringConfig);

    final PGPSecretKeyRingCollection secretKeyRings = keyringConfig.getSecretKeyRings();

    switch (purpose) {
      case FOR_SIGNING:
        return publicKeyrings.stream()
            .flatMap(keyring -> StreamSupport.stream(keyring.spliterator(), false))
            .filter(this::isNotMasterKey)
            .filter(this::isVerificationKey)
            .filter(this::isNotRevoked)
            .filter(this::isNotExpired)
            .filter(hasPrivateKey(secretKeyRings))
            .reduce((a, b) -> b)
            .orElse(null);

      case FOR_ENCRYPTION:
        return publicKeyrings.stream()
            .flatMap(keyring -> StreamSupport.stream(keyring.spliterator(), false))
            .filter(this::isEncryptionKey)
            .filter(this::isNotRevoked)
            .filter(this::isNotExpired)
            .reduce((a, b) -> b)
            .orElse(null);

      default:
        return null;
    }
  }


  protected Predicate<PGPPublicKey> hasPrivateKey(final PGPSecretKeyRingCollection secretKeyRings) {
    return p -> {
      try {
        return secretKeyRings.contains(p.getKeyID());
      } catch (PGPException e) {
        // ignore this for filtering
        LOGGER.debug("Failed to test for private key for pubkey " + p.getKeyID());
        return false;
      }
    };
  }


  protected boolean isNotMasterKey(PGPPublicKey p) {
    return !p.isMasterKey();
  }

  protected boolean isNotExpired(PGPPublicKey p) {
    // getValidSeconds == 0 means: no expiration date
    boolean hasExpiryDate = p.getValidSeconds() > 0;
    final boolean isExpired;

    if (hasExpiryDate) {
      isExpired = p.getCreationTime().toInstant()
          .plusSeconds(p.getValidSeconds())
          .isBefore(getDateOfTimestampVerification());
    } else {
      isExpired = false;
    }
    return !isExpired;
  }


  protected boolean isEncryptionKey(PGPPublicKey publicKey) {
    final long keyFlags = extractPublicKeyFlags(publicKey);
    return (keyFlags & PGPKeyFlags.CAN_ENCRYPT_COMMS) == PGPKeyFlags.CAN_ENCRYPT_COMMS
        || (keyFlags & PGPKeyFlags.CAN_ENCRYPT_STORAGE) == PGPKeyFlags.CAN_ENCRYPT_STORAGE;
  }

  protected boolean isVerificationKey(PGPPublicKey publicKey) {
    return (extractPublicKeyFlags(publicKey) & PGPKeyFlags.CAN_SIGN) == PGPKeyFlags.CAN_SIGN;
  }

  protected boolean isNotRevoked(PGPPublicKey publicKey) {
    return !publicKey.hasRevocation();
  }

  protected long extractPublicKeyFlags(PGPPublicKey publicKey) {
    long aggregatedKeyFlags = 0;

    final Iterator<PGPSignature> directKeySignatures = publicKey.getSignatures();

    while (directKeySignatures.hasNext()) {
      final PGPSignature signature = directKeySignatures.next();
      final PGPSignatureSubpacketVector hashedSubPackets = signature.getHashedSubPackets();

      final int keyFlags = hashedSubPackets.getKeyFlags();
      aggregatedKeyFlags |= keyFlags;
    }
    return aggregatedKeyFlags;
  }

}


