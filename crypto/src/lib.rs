//! Olm for The Bridge's direct messages: a thin wrapper around vodozemac, exported to Kotlin and Swift with UniFFI.
//!
//! Each app install owns one [`OlmAccount`] (its identity keys and one-time keys) and one [`OlmSession`] per
//! device it talks to. Accounts and sessions are stored on the device as pickles encrypted with a 32-byte key
//! that lives in the Android Keystore / iOS Keychain. Nothing here touches the network.

use std::sync::{Arc, Mutex};

use sha2::{Digest, Sha512};
use vodozemac::olm::{Account, AccountPickle, OlmMessage, Session, SessionConfig, SessionPickle};
use vodozemac::{base64_decode, base64_encode, Curve25519PublicKey};

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CryptoError {
    #[error("bad key: {0}")]
    BadKey(String),
    #[error("bad message: {0}")]
    BadMessage(String),
    #[error("could not decrypt: {0}")]
    Decrypt(String),
    #[error("bad pickle: {0}")]
    Pickle(String),
}

#[derive(uniffi::Record)]
pub struct IdentityKeys {
    pub curve25519: String,
    pub ed25519: String,
}

#[derive(uniffi::Record)]
pub struct OneTimeKey {
    pub id: String,
    pub key: String,
}

/// An Olm message as sent over the wire: type 0 = pre-key (first message of a session), 1 = normal. [body] is base64.
#[derive(uniffi::Record)]
pub struct Encrypted {
    pub msg_type: u8,
    pub body: String,
}

/// A session created from someone's first message, with that message's plaintext.
#[derive(uniffi::Record)]
pub struct Inbound {
    pub session: Arc<OlmSession>,
    pub plaintext: String,
}

fn pickle_key(key: &[u8]) -> Result<[u8; 32], CryptoError> {
    key.try_into().map_err(|_| CryptoError::Pickle("the pickle key must be 32 bytes".into()))
}

fn curve(key: &str) -> Result<Curve25519PublicKey, CryptoError> {
    Curve25519PublicKey::from_base64(key).map_err(|e| CryptoError::BadKey(e.to_string()))
}

fn message(msg_type: u8, body: &str) -> Result<OlmMessage, CryptoError> {
    let bytes = base64_decode(body).map_err(|e| CryptoError::BadMessage(e.to_string()))?;
    OlmMessage::from_parts(msg_type as usize, &bytes).map_err(|e| CryptoError::BadMessage(e.to_string()))
}

#[derive(uniffi::Object)]
pub struct OlmAccount {
    inner: Mutex<Account>,
}

#[uniffi::export]
impl OlmAccount {
    /// A fresh account with new identity keys (first launch, or after a reinstall).
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self { inner: Mutex::new(Account::new()) })
    }

    #[uniffi::constructor]
    pub fn from_pickle(pickle: String, key: Vec<u8>) -> Result<Arc<Self>, CryptoError> {
        let p = AccountPickle::from_encrypted(&pickle, &pickle_key(&key)?).map_err(|e| CryptoError::Pickle(e.to_string()))?;
        Ok(Arc::new(Self { inner: Mutex::new(Account::from_pickle(p)) }))
    }

    pub fn pickle(&self, key: Vec<u8>) -> Result<String, CryptoError> {
        Ok(self.inner.lock().unwrap().pickle().encrypt(&pickle_key(&key)?))
    }

    pub fn identity_keys(&self) -> IdentityKeys {
        let k = self.inner.lock().unwrap().identity_keys();
        IdentityKeys { curve25519: k.curve25519.to_base64(), ed25519: k.ed25519.to_base64() }
    }

    /// Ed25519 signature of [message] with this device's identity key.
    pub fn sign(&self, message: String) -> String {
        self.inner.lock().unwrap().sign(message.as_bytes()).to_base64()
    }

    /// Makes [count] new one-time keys and returns every key not yet published.
    pub fn generate_one_time_keys(&self, count: u32) -> Vec<OneTimeKey> {
        let mut a = self.inner.lock().unwrap();
        a.generate_one_time_keys(count as usize);
        a.one_time_keys().into_iter().map(|(id, k)| OneTimeKey { id: id.to_base64(), key: k.to_base64() }).collect()
    }

    /// Rotates the fallback key (used when one-time keys run out) and returns it.
    pub fn generate_fallback_key(&self) -> Option<OneTimeKey> {
        let mut a = self.inner.lock().unwrap();
        a.generate_fallback_key();
        a.fallback_key().into_iter().next().map(|(id, k)| OneTimeKey { id: id.to_base64(), key: k.to_base64() })
    }

    /// Call after the server accepted the keys from [generate_one_time_keys] / [generate_fallback_key].
    pub fn mark_keys_as_published(&self) {
        self.inner.lock().unwrap().mark_keys_as_published();
    }

    /// Starts a session with another device from its identity key and a one-time key claimed from the server.
    pub fn create_outbound_session(&self, their_identity_key: String, their_one_time_key: String) -> Result<Arc<OlmSession>, CryptoError> {
        let a = self.inner.lock().unwrap();
        let s = a.create_outbound_session(SessionConfig::version_1(), curve(&their_identity_key)?, curve(&their_one_time_key)?);
        Ok(Arc::new(OlmSession { inner: Mutex::new(s) }))
    }

    /// Creates the session from someone's first (pre-key) message and decrypts it. Uses up the one-time key it names.
    pub fn create_inbound_session(&self, their_identity_key: String, body: String) -> Result<Inbound, CryptoError> {
        let OlmMessage::PreKey(m) = message(0, &body)? else { return Err(CryptoError::BadMessage("not a pre-key message".into())) };
        let mut a = self.inner.lock().unwrap();
        let r = a.create_inbound_session(curve(&their_identity_key)?, &m).map_err(|e| CryptoError::Decrypt(e.to_string()))?;
        let plaintext = String::from_utf8(r.plaintext).map_err(|e| CryptoError::Decrypt(e.to_string()))?;
        Ok(Inbound { session: Arc::new(OlmSession { inner: Mutex::new(r.session) }), plaintext })
    }
}

#[derive(uniffi::Object)]
pub struct OlmSession {
    inner: Mutex<Session>,
}

#[uniffi::export]
impl OlmSession {
    #[uniffi::constructor]
    pub fn from_pickle(pickle: String, key: Vec<u8>) -> Result<Arc<Self>, CryptoError> {
        let p = SessionPickle::from_encrypted(&pickle, &pickle_key(&key)?).map_err(|e| CryptoError::Pickle(e.to_string()))?;
        Ok(Arc::new(Self { inner: Mutex::new(Session::from_pickle(p)) }))
    }

    pub fn pickle(&self, key: Vec<u8>) -> Result<String, CryptoError> {
        Ok(self.inner.lock().unwrap().pickle().encrypt(&pickle_key(&key)?))
    }

    pub fn session_id(&self) -> String {
        self.inner.lock().unwrap().session_id()
    }

    pub fn encrypt(&self, plaintext: String) -> Encrypted {
        let (t, body) = self.inner.lock().unwrap().encrypt(plaintext.as_bytes()).to_parts();
        Encrypted { msg_type: t as u8, body: base64_encode(body) }
    }

    pub fn decrypt(&self, msg_type: u8, body: String) -> Result<String, CryptoError> {
        let m = message(msg_type, &body)?;
        let bytes = self.inner.lock().unwrap().decrypt(&m).map_err(|e| CryptoError::Decrypt(e.to_string()))?;
        String::from_utf8(bytes).map_err(|e| CryptoError::Decrypt(e.to_string()))
    }
}

/// The safety number two people compare in person: 60 digits in 12 groups of 5, the same on both phones
/// (the two Ed25519 identity keys are sorted before hashing). It changes when either side reinstalls.
#[uniffi::export]
pub fn safety_number(my_ed25519: String, their_ed25519: String) -> Vec<String> {
    let (a, b) = if my_ed25519 <= their_ed25519 { (my_ed25519, their_ed25519) } else { (their_ed25519, my_ed25519) };
    let mut digest = Sha512::digest(format!("bridge-safety-v1|{a}|{b}").as_bytes()).to_vec();
    // 5,200 rounds slows down anyone searching for a key with a matching number.
    for _ in 0..5200 { digest = Sha512::digest(&digest).to_vec(); }
    digest.chunks(5).take(12).map(|c| {
        let n = c.iter().fold(0u64, |acc, b| (acc << 8) | *b as u64) % 100_000;
        format!("{n:05}")
    }).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEY: [u8; 32] = [7; 32];

    #[test]
    fn two_devices_talk_both_ways_and_survive_a_restart() {
        let alice = OlmAccount::new();
        let bob = OlmAccount::new();
        let bob_otk = bob.generate_one_time_keys(1).remove(0);
        bob.mark_keys_as_published();

        let out = alice.create_outbound_session(bob.identity_keys().curve25519, bob_otk.key).unwrap();
        let first = out.encrypt("Morning! Run at 7?".into());
        assert_eq!(first.msg_type, 0);
        let Inbound { session: inb, plaintext } = bob.create_inbound_session(alice.identity_keys().curve25519, first.body).unwrap();
        assert_eq!(plaintext, "Morning! Run at 7?");
        assert_eq!(inb.session_id(), out.session_id());

        let reply = inb.encrypt("Yes, see you there".into());
        assert_eq!(out.decrypt(reply.msg_type, reply.body).unwrap(), "Yes, see you there");
        let next = out.encrypt("Great".into());
        assert_eq!(next.msg_type, 1);

        // Both sides store and restore their state
        let restored = OlmSession::from_pickle(inb.pickle(KEY.to_vec()).unwrap(), KEY.to_vec()).unwrap();
        assert_eq!(restored.decrypt(next.msg_type, next.body).unwrap(), "Great");
        let again = OlmAccount::from_pickle(alice.pickle(KEY.to_vec()).unwrap(), KEY.to_vec()).unwrap();
        assert_eq!(again.identity_keys().ed25519, alice.identity_keys().ed25519);
        assert!(OlmAccount::from_pickle(alice.pickle(KEY.to_vec()).unwrap(), vec![1; 32]).is_err());
    }

    #[test]
    fn a_one_time_key_works_once() {
        let alice = OlmAccount::new();
        let bob = OlmAccount::new();
        let otk = bob.generate_one_time_keys(1).remove(0);
        let m = alice.create_outbound_session(bob.identity_keys().curve25519, otk.key).unwrap().encrypt("hi".into());
        assert!(bob.create_inbound_session(alice.identity_keys().curve25519, m.body.clone()).is_ok());
        assert!(bob.create_inbound_session(alice.identity_keys().curve25519, m.body).is_err());
    }

    #[test]
    fn tampered_messages_fail() {
        let alice = OlmAccount::new();
        let bob = OlmAccount::new();
        let otk = bob.generate_one_time_keys(1).remove(0);
        let out = alice.create_outbound_session(bob.identity_keys().curve25519, otk.key).unwrap();
        let first = out.encrypt("hi".into());
        let inb = bob.create_inbound_session(alice.identity_keys().curve25519, first.body).unwrap().session;
        let m = out.encrypt("secret".into());
        let mut body = m.body.into_bytes();
        // Flip a character in the authenticated ciphertext near the end (the base64 has no padding).
        let i = body.len() - 4;
        body[i] = if body[i] == b'A' { b'B' } else { b'A' };
        assert!(inb.decrypt(m.msg_type, String::from_utf8(body).unwrap()).is_err());
    }

    #[test]
    fn safety_numbers_match_on_both_phones() {
        let a = OlmAccount::new().identity_keys().ed25519;
        let b = OlmAccount::new().identity_keys().ed25519;
        let mine = safety_number(a.clone(), b.clone());
        assert_eq!(mine, safety_number(b.clone(), a.clone()));
        assert_eq!(mine.len(), 12);
        assert!(mine.iter().all(|g| g.len() == 5 && g.chars().all(|c| c.is_ascii_digit())));
        assert_ne!(mine, safety_number(a, OlmAccount::new().identity_keys().ed25519));
    }

    #[test]
    fn signs_with_the_identity_key() {
        let a = OlmAccount::new();
        let sig = a.sign("device:abc".into());
        let key = vodozemac::Ed25519PublicKey::from_base64(&a.identity_keys().ed25519).unwrap();
        let sig = vodozemac::Ed25519Signature::from_base64(&sig).unwrap();
        assert!(key.verify(b"device:abc", &sig).is_ok());
    }
}
