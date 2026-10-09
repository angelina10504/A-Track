<?php
/**
 * sos_notify.php — emails the office when an SOS record (datatype 90) arrives.
 *
 * Included by sync_locations.php, which calls sosNotify() right after it has stored a
 * NEW SOS row. Re-sent duplicates of the same record do not trigger another email.
 *
 * This file only defines a function. Opening it in a browser does nothing.
 *
 * Mail settings are read from send_report.php (the Brevo login that already works for
 * report emails), so there is no second copy of the SMTP key to keep in step.
 */

use PHPMailer\PHPMailer\PHPMailer;

// Who receives SOS emails. Leave empty to use OFFICE_EMAIL from send_report.php.
// Example: const SOS_RECIPIENTS = ['office@example.com', 'supervisor@example.com'];
const SOS_RECIPIENTS = [];

/** Reads one  const NAME = 'value';  line out of send_report.php without running it. */
function sosReadMailSetting(string $name): string {
    static $source = null;
    if ($source === null) {
        $source = (string)@file_get_contents(__DIR__ . '/send_report.php');
    }
    if (preg_match("/const\\s+" . preg_quote($name, '/') . "\\s*=\\s*'([^']*)'/", $source, $m)) {
        return $m[1];
    }
    if (preg_match("/const\\s+" . preg_quote($name, '/') . "\\s*=\\s*(\\d+)/", $source, $m)) {
        return $m[1];
    }
    return '';
}

/**
 * Sends the SOS email. Never throws and never prints anything, so a mail problem
 * cannot break the sync response the phone is waiting for.
 *
 * @param int $timeMs  time of the SOS in milliseconds (the record's dateTime)
 * @return true|string true on success, otherwise the reason it failed
 */
function sosNotify(string $mobile, float $lat, float $lng, int $timeMs, int $battery,
                   string $textMsg, int $recNo) {
    try {
        require_once __DIR__ . '/vendor/autoload.php';

        $host = sosReadMailSetting('SMTP_HOST');
        $user = sosReadMailSetting('SMTP_USER');
        $pass = sosReadMailSetting('SMTP_PASS');
        $from = sosReadMailSetting('FROM_EMAIL');
        $port = (int)(sosReadMailSetting('SMTP_PORT') ?: 587);

        $recipients = SOS_RECIPIENTS;
        if (count($recipients) === 0) {
            $office = sosReadMailSetting('OFFICE_EMAIL');
            if ($office !== '') $recipients = [$office];
        }
        if ($host === '' || $user === '' || $pass === '' || $from === '' || count($recipients) === 0) {
            $why = 'mail settings could not be read from send_report.php';
            error_log('sosNotify: ' . $why);
            return $why;
        }

        $when    = date('d-m-Y H:i:s', (int)($timeMs / 1000));   // server timezone (IST)
        $hasFix  = !($lat == 0.0 && $lng == 0.0);
        $mapLink = $hasFix ? 'https://maps.google.com/?q=' . $lat . ',' . $lng : 'not available';

        $mail = new PHPMailer(true);
        $mail->isSMTP();
        $mail->Host       = $host;
        $mail->SMTPAuth   = true;
        $mail->Username   = $user;
        $mail->Password   = $pass;
        $mail->SMTPSecure = PHPMailer::ENCRYPTION_STARTTLS;
        $mail->Port       = $port;
        $mail->CharSet    = 'UTF-8';
        $mail->Timeout    = 8;        // the phone is waiting on this request
        $mail->Priority   = 1;        // flagged as high importance in mail clients

        $mail->setFrom($from, 'ROU Track SOS');
        foreach ($recipients as $to) {
            $mail->addAddress($to);
        }

        $mail->Subject = 'SOS ALERT - ' . $mobile . ' - ' . $when;
        $mail->Body =
            "An SOS was sent from the ROU Track app.\n\n" .
            "Mobile:    " . $mobile . "\n" .
            "Time:      " . $when . "\n" .
            "Location:  " . ($hasFix ? $lat . ', ' . $lng : 'not available') . "\n" .
            "Map:       " . $mapLink . "\n" .
            "Battery:   " . $battery . "%\n" .
            "Record no: " . $recNo . "\n\n" .
            "Phone status at the time:\n" . $textMsg . "\n\n" .
            "Received by server: " . date('d-m-Y H:i:s') . "\n";

        $mail->send();
        return true;
    } catch (\Throwable $e) {
        $why = isset($mail) ? $mail->ErrorInfo : $e->getMessage();
        if ($why === '') $why = $e->getMessage();
        error_log('sosNotify failed for ' . $mobile . ': ' . $why);
        return $why;
    }
}
