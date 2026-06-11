# Server-side report mailer (`send_report.php`)

This replaces the old in-app Gmail SMTP sending (which kept hitting Google's
`534-5.7.9 WebLoginRequired` block). The app now uploads the CSV to the server and
the **server** sends the email. Because the server has a single, stable IP,
Gmail no longer flags the login as suspicious.

## Why this fixes the error for good
- Field devices roam across many networks/IPs → Gmail sees "logins from everywhere"
  → blocks SMTP and demands a web login. That can never be fully avoided client-side.
- One server IP sending mail looks normal → no block.
- Bonus: **no email credentials ship inside the APK** anymore.

## Deploy steps
1. Copy `send_report.php` to your API root next to the other endpoints:
   `https://droneaeromatix.com/api2/send_report.php`
2. Install PHPMailer in that folder:
   ```
   composer require phpmailer/phpmailer
   ```
   (or download PHPMailer manually and fix the `require` path in the script).
3. Edit the `CONFIG` block at the top of `send_report.php`:
   - `OFFICE_EMAIL` – where reports go (change here anytime, no app update needed).
   - `SMTP_USER` / `SMTP_PASS` – the sending Gmail account + a **freshly generated
     App Password** (myaccount.google.com/apppasswords). Put it ONLY here on the server.
   - Prefer your hosting provider's SMTP (e.g. `mail.droneaeromatix.com`) if available —
     it's usually more reliable for server-to-server mail than Gmail.
4. (Recommended) Set `SHARED_SECRET` to a random string and post the same value as a
   form field `apiKey` from the app, so random callers can't use your mailer as a relay.

## Contract (must match the app)
**POST** `multipart/form-data`:
| field          | type | notes                          |
|----------------|------|--------------------------------|
| `mobileNumber` | text | for the subject / logging      |
| `subject`      | text | email subject                  |
| `body`         | text | email body                     |
| `report`       | file | the CSV attachment             |

**Response (JSON):** `{"success":true}` or `{"success":false,"message":"..."}`

## Security notes
- The recipient is fixed server-side on purpose — the app cannot make the server send
  to arbitrary addresses (prevents open-relay/spam abuse).
- Keep `SMTP_PASS` out of any public git repo. Ideally load it from an environment
  variable or a config file stored outside the web root.
