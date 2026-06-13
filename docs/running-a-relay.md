# Run an RCQ relay (help people get through censorship)

A **relay** is a blind tunnel. It carries obfuscated traffic for RCQ users whose
network blocks the app, then forwards it on. It is the "гидра": the more relays
people run, the harder RCQ is to block — block one, the rest live.

**A relay never sees your messages.** Traffic is end-to-end encrypted, the sender
is hidden (sealed sender), and with onion routing on, a single relay cannot link
*who* to *which server* — it only sees opaque bytes passing through. Running one
exposes you to nothing about anyone's conversations.

## What you need
- A cheap VPS (1 vCPU / 1 GB is plenty), Ubuntu or Debian, **root** access.
- Port **443** open (TCP).
- That's it. No RCQ account, no approval step — your relay registers itself.

## One command

SSH into your fresh VPS as root and run:

```bash
curl -fsSL https://raw.githubusercontent.com/rcq-messenger/rcq-android/main/relay/relay-bootstrap.sh | bash
```

(or `scp` the script and run it locally). It will:
1. install sing-box,
2. generate fresh VLESS + Reality credentials,
3. open port 443,
4. start the relay as a systemd service,
5. **register itself with the RCQ broker** — so users start getting it
   automatically, with no manual approval.

When it finishes it prints your relay's parameters and confirms registration.

### Options
- `RCQ_RELAY_SNI=www.yandex.ru` — the domain your relay impersonates (Reality).
  Pick a big, locally-popular HTTPS site. Default: `www.microsoft.com`. For a
  relay inside Russia, an intra-RF site like `www.yandex.ru` looks most innocuous.
- `RCQ_NO_REGISTER=1` — set up the relay but **don't** auto-register (you'll
  register manually later).
- `RCQ_BROKER=https://api.rcq.app` — the broker to register with (default).

Re-running the script on the same box refreshes the **same** registration (it
keeps your operator key at `/etc/sing-box/rcq-operator-ed25519.b64` — back it up
if you want to move the relay later).

## How your relay reaches users
RCQ doesn't publish the whole relay list publicly (a censor would just block them
all). The **broker** hands relays out a few-at-a-time per request, bucketed by
network, so the pool can't be scraped wholesale. Your relay joins that pool and
is distributed to users who need a way through.

## Trust & safety
- Community relays are used as **extra capacity / exit hops**; the metadata-
  sensitive **entry** hop stays on a vetted relay, so even a malicious relay
  learns nothing useful (with onion on, it can't see both your IP and your
  destination).
- A dead or unreachable relay is simply not handed out — you don't need to babysit
  it, but keeping it up helps people.

Questions or a relay you want promoted to the trusted tier? Reach us in-app at
RCQ **#911** or `security@rcq.app`.
