<?php
/**
 * send_report.php  —  ROU Track location-report mailer  (Brevo SMTP)
 *
 * Receives a CSV (multipart/form-data) from the Android app and emails it to the
 * office. SMTP credentials live HERE on the server, never in the APK.
 *
 * Sends via Brevo (formerly Sendinblue) instead of Gmail. Gmail is a personal
 * mailbox and repeatedly blocked automated server sending — first with a 534
 * anti-abuse block, then "SMTP Error: Could not authenticate" after the app
 * password was rotated. Brevo is a transactional provider built for this.
 * Free tier: 300 emails/day.
 *
 * Deploy to:  https://droneaeromatix.com/api2/send_report.php
 *
 * The Android app posts (see ApiService.sendReportEmail) — UNCHANGED, so no APK
 * update is needed to switch providers:
 *   - mobileNumber  (text)
 *   - subject       (text)
 *   - body          (text)
 *   - report        (file, the CSV)
 * and expects a JSON reply: {"success":true}  or  {"success":false,"message":"..."}
 *
 * Requires PHPMailer:   composer require phpmailer/phpmailer
 */

use PHPMailer\PHPMailer\PHPMailer;
use PHPMailer\PHPMailer\Exception;

header('Content-Type: application/json');
require __DIR__ . '/vendor/autoload.php';   // adjust path if PHPMailer lives elsewhere

// ─── CONFIG (edit these) ──────────────────────────────────────────────────────

// Where reports are sent. Recipients need NO verification in Brevo.
const OFFICE_EMAIL   = 'routrackemailreceiver@gmail.com';

// Brevo SMTP relay. Port 587 = STARTTLS (use 2525 if your host blocks 587).
const SMTP_HOST      = 'smtp-relay.brevo.com';
const SMTP_PORT      = 587;

// Brevo LOGIN — from Brevo dashboard → SMTP & API → SMTP tab.
// This is NOT your Brevo account email; it looks like "9xxxxx001@smtp-brevo.com".
const SMTP_USER      = 'PUT_BREVO_SMTP_LOGIN_HERE';

// Brevo SMTP KEY (same screen). Server-only — never commit the real value.
const SMTP_PASS      = 'PUT_BREVO_SMTP_KEY_HERE';

// IMPORTANT: the From address must be a VERIFIED SENDER in Brevo
// (dashboard → Senders, Domains & Dedicated IPs → Senders → Add a sender,
// then click the confirmation link Brevo emails you).
// This is deliberately SEPARATE from SMTP_USER — using the SMTP login as the
// From address is the most common reason Brevo rejects a message.
const FROM_EMAIL     = 'PUT_VERIFIED_SENDER_EMAIL_HERE';
const SMTP_FROM_NAME = 'ROU Track';

// Optional anti-abuse: set a non-empty secret here AND post the same value as a
// form field "apiKey" from the app. Leave '' to disable.
const SHARED_SECRET  = '';
// ──────────────────────────────────────────────────────────────────────────────

function reply(bool $ok, string $msg = ''): void {
    echo json_encode($ok ? ['success' => true]
                         : ['success' => false, 'message' => $msg]);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    reply(false, 'POST required');
}
if (SHARED_SECRET !== '' && ($_POST['apiKey'] ?? '') !== SHARED_SECRET) {
    http_response_code(401);
    reply(false, 'Unauthorized');
}

// Validate the uploaded CSV
if (!isset($_FILES['report']) || $_FILES['report']['error'] !== UPLOAD_ERR_OK) {
    reply(false, 'No report file received');
}
$tmpPath  = $_FILES['report']['tmp_name'];
$fileName = basename($_FILES['report']['name']);
if (!is_uploaded_file($tmpPath)) {
    reply(false, 'Invalid upload');
}

$mobile  = $_POST['mobileNumber'] ?? 'unknown';
$subject = $_POST['subject'] ?? ('ROU Track Report - ' . $mobile);
$body    = $_POST['body'] ?? 'Please find the attached location report.';

$mail = new PHPMailer(true);
try {
    $mail->isSMTP();
    $mail->Host       = SMTP_HOST;
    $mail->SMTPAuth   = true;
    $mail->Username   = SMTP_USER;
    $mail->Password   = SMTP_PASS;
    $mail->SMTPSecure = PHPMailer::ENCRYPTION_STARTTLS;
    $mail->Port       = SMTP_PORT;
    $mail->CharSet    = 'UTF-8';   // keeps non-ASCII in subject/body intact

    // From must be the VERIFIED sender, not the SMTP login (see note above).
    $mail->setFrom(FROM_EMAIL, SMTP_FROM_NAME);
    $mail->addAddress(OFFICE_EMAIL);          // server-controlled recipient (anti-abuse)
    $mail->addReplyTo(FROM_EMAIL, SMTP_FROM_NAME);

    $mail->Subject = $subject;
    $mail->Body    = $body;
    $mail->addAttachment($tmpPath, $fileName, PHPMailer::ENCODING_BASE64, 'text/csv');

    $mail->send();
    reply(true);
} catch (Exception $e) {
    http_response_code(500);
    reply(false, 'Mailer error: ' . $mail->ErrorInfo);
}
