# SUPER RIDEX website

Static marketing site for **SUPER RIDEX** — ready for GitHub Pages + a custom domain.

## Pages

- `index.html` — Home
- `about.html` — About
- `contact.html` — Contact (WhatsApp, Instagram, YouTube)
- `privacy.html` — Privacy Policy
- `terms.html` — Terms & Conditions

## Before you publish

1. **Put your APK here:** copy the Android build to `downloads/superridex.apk` (all Download buttons point to this file).
2. **Custom domain:** edit `CNAME` to your real domain (e.g. `www.superridex.com` or `superridex.com`).
3. **SEO URLs:** replace `https://superridex.com` in `index.html`, other pages, `robots.txt`, and `sitemap.xml` if your domain differs.
4. **Instagram:** Contact uses `https://www.instagram.com/super_ride_x/` to match the YouTube handle — update `contact.html` if your handle is different.

## GitHub Pages

1. Create a repo and push this folder to `main`.
2. Settings → Pages → Source: Deploy from branch → `main` / root (or `/docs` if you move files).
3. Add your custom domain in Pages settings; DNS: `A`/`AAAA` or `CNAME` as GitHub instructs.
4. Wait for HTTPS to become active.

## Local preview

Open `index.html` in a browser, or from this folder run:

```bash
npx --yes serve .
```
