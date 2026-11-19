# Deploying Sadak on AWS EC2

One small Ubuntu server runs everything: Sadak, its PostgreSQL database, and Caddy, a web server that gets an HTTPS certificate by itself. It runs in demo mode: the very first start seeds the synthetic Punjab company (8 weeks of history, trucks on the road), and the sign-in page opens on **Try the demo**, one click per role.

You need an AWS account and this repository on GitHub. About 20 minutes the first time.

## 1. Launch the server

In the AWS console, **EC2 → Launch instance**:

| Setting | Value |
|---|---|
| Name | `sadak` |
| Image | **Ubuntu Server 24.04 LTS** |
| Instance type | **t3.small** (2 GB). t3.micro (1 GB, free tier) also works, just slower to build |
| Key pair | Create one (for example `sadak-key`) and keep the downloaded `.pem` file |
| Storage | 16 GB |
| Network | Allow **SSH from My IP**, **HTTP from anywhere** and **HTTPS from anywhere** |

Then **EC2 → Elastic IPs → Allocate**, and **Associate** it with the instance. The address then stays the same when the server restarts. Note it down, for example `13.234.56.78`.

## 2. Connect

From PowerShell on your laptop, in the folder with the key:

```bash
ssh -i sadak-key.pem ubuntu@13.234.56.78
```

If Windows complains that the key's permissions are too open, right-click it → Properties → Security → Advanced, disable inheritance and leave only your own user.

## 3. Get the code onto the server

```bash
git clone https://github.com/rabeeh18/sadak.git
cd sadak
```

If the repository is private, GitHub asks for a username and password: use your GitHub username and a **personal access token** (GitHub → Settings → Developer settings → Personal access tokens → fine-grained, read-only access to this repository) as the password.

## 4. Run the setup

```bash
sudo bash deploy/setup-ec2.sh
```

It takes 5–10 minutes. At the end it prints the address, for example:

```
Sadak is up:  https://13-234-56-78.sslip.io
```

That address works straight away with HTTPS: `sslip.io` is a free service that turns any IP into a name. Give your professor `https://13-234-56-78.sslip.io`: the page opens on **Try the demo**, where they pick a role and look around.

**Have your own domain?** Point an `A` record at the Elastic IP, then run `sudo bash deploy/setup-ec2.sh sadak.yourdomain.com`.

## Afterwards

| Task | Command (on the server, in `~/sadak`) |
|---|---|
| Put new changes live (after `git push` on your laptop) | `sudo bash deploy/update.sh` |
| Start the demo over with fresh data | **Reset the demo** under the roles on the sign-in page, or `sudo bash deploy/reset-demo.sh` |
| Is it running? | `systemctl status sadak` |
| See the log | `journalctl -u sadak -f` |
| Restart | `sudo systemctl restart sadak` |

The settings and database password are in `/etc/sadak/sadak.env` (readable by root only).

## Good to know

- **It keeps running.** Sadak restarts by itself if it crashes or the server reboots. The demo also keeps dispatching trips by itself, so it never runs dry.
- **Cost.** A t3.small in Mumbai is roughly $15–18 a month while running; t3.micro is free for the first year on a new account. Stop the instance in the console when nobody needs it: the Elastic IP keeps its address (AWS charges a little for it while the instance is stopped).
- **Memory.** Sadak uses about 400 MB and PostgreSQL about 100 MB. The setup adds 2 GB of swap so building on a small instance doesn't run out of memory.
