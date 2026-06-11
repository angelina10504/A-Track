<?php
/**
 * send_report.php  —  ROU Track location-report mailer
 *
 * Receives a CSV (multipart/form-data) from the Android app and emails it to the
 * office. SMTP credentials live HERE on the server (stable IP), so Gmail's
 * anti-abuse does NOT block sends the way it does for many roaming field devices.
 *
 * Deploy to:  https://droneaeromatix.com/api2/send_report.php
 *
 * The Android app posts (see ApiService.sendReportEmail):
 *   - mobileNumber  (text)
 *   - subject       (text)
 *   - body          (text)
 *   - report        (file, the CSV)
 * and expects a JSON reply: {"success":true}  or  {"success":false,"message":"..."}
 *
 * Requires PHPMailer:   composer require phpmailer/phpmailer
 *   (or download it and point the require() below at PHPMailerAutoload.php)
 */

use PHPMailer\PHPMailer\PHPMailer;
use PHPMailer\PHPMailer\Exception;

header('Content-Type: application/json');
require __DIR__ . '/vendor/autoload.php';   // adjust path if PHPMailer lives elsewhere

// ─── CONFIG (edit these) ──────────────────────────────────────────────────────
const OFFICE_EMAIL   = 'routrackemailreceiver@gmail.com';  // where reports are sent
const SMTP_HOST      = 'smtp.gmail.com';
const SMTP_PORT      = 587;
const SMTP_USER      = 'a.track1234@gmail.com';            // sending account
const SMTP_PASS      = 'PUT_NEW_APP_PASSWORD_HERE';        // NEW Gmail App Password (server-only!)
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

    $mail->setFrom(SMTP_USER, SMTP_FROM_NAME);
    $mail->addAddress(OFFICE_EMAIL);          // server-controlled recipient (anti-abuse)
    $mail->addReplyTo(SMTP_USER, SMTP_FROM_NAME);

    $mail->Subject = $subject;
    $mail->Body    = $body;
    $mail->addAttachment($tmpPath, $fileName, PHPMailer::ENCODING_BASE64, 'text/csv');

    $mail->send();
    reply(true);
} catch (Exception $e) {
    http_response_code(500);
    reply(false, 'Mailer error: ' . $mail->ErrorInfo);
}
