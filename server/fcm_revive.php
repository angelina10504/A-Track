<?php
/**
 * fcm_revive.php — pushes "restart_tracking" to devices that have gone quiet.
 *
 * COMMAND LINE ONLY. Run from cron every 5 minutes (see server/README.md):
 *     php /usr/share/api2/fcm_revive.php
 *
 * Manual test (sends one message to one user and prints Firebase's reply):
 *     php /usr/share/api2/fcm_revive.php --test 9822436151 ping
 *     php /usr/share/api2/fcm_revive.php --test 9822436151 restart_tracking
 *
 * Dry run (lists who WOULD be pushed, sends nothing):
 *     php /usr/share/api2/fcm_revive.php --dry-run
 *
 * Needs no PHP libraries — only the openssl extension, which PHP already uses
 * for HTTPS.
 */

if (php_sapi_name() !== 'cli') {
    http_response_code(403);
    exit('Forbidden');
}

// ─── CONFIG ───────────────────────────────────────────────────────────────────

// Service-account key downloaded from Firebase. Keep it OUTSIDE the web root.
const FCM_KEY_FILE = '/etc/routrack-fcm-key.json';

// A device is "quiet" when the server has received nothing from it for this long.
// The app saves at least one record every 15 min and syncs every ~2 min, so a
// healthy device never goes 25 min without data.
const QUIET_AFTER_MINUTES = 25;

// Do not push the same device more often than this.
const REPUSH_EVERY_MINUTES = 15;

// Stop trying once a device has been silent this long. Its user has most likely
// logged out, switched the phone off, or finished for the day.
const GIVE_UP_AFTER_HOURS = 6;

// Table and columns that hold the synced location rows (see sync_locations.php).
const DATA_TABLE       = 'data_yday_avt';
const DATA_MOBILE_COL  = 'imei';
const DATA_TIME_COL    = 'server_date_time';

// ──────────────────────────────────────────────────────────────────────────────

// config.php checks the request method, which does not exist on the command line.
$_SERVER['REQUEST_METHOD'] = 'CLI';
require_once __DIR__ . '/config.php';
mysqli_report(MYSQLI_REPORT_OFF);

function logLine(string $msg): void {
    echo '[' . date('Y-m-d H:i:s') . '] ' . $msg . "\n";
}

function base64Url(string $data): string {
    return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
}

/** Plain HTTPS POST. Returns [httpStatus, body]. */
function httpPost(string $url, array $headers, string $body): array {
    $context = stream_context_create([
        'http' => [
            'method'        => 'POST',
            'header'        => implode("\r\n", $headers),
            'content'       => $body,
            'timeout'       => 20,
            'ignore_errors' => true,   // still return the body on 4xx/5xx
        ],
    ]);
    $response = @file_get_contents($url, false, $context);
    $status = 0;
    if (isset($http_response_header[0])
            && preg_match('#\s(\d{3})\s#', $http_response_header[0] . ' ', $m)) {
        $status = (int)$m[1];
    }
    return [$status, $response === false ? '' : $response];
}

/** Loads the service-account key file, or exits with a clear message. */
function loadServiceAccount(): array {
    if (!is_readable(FCM_KEY_FILE)) {
        logLine('ERROR: cannot read ' . FCM_KEY_FILE . ' (check path and permissions)');
        exit(1);
    }
    $sa = json_decode(file_get_contents(FCM_KEY_FILE), true);
    if (!is_array($sa) || empty($sa['client_email']) || empty($sa['private_key'])
            || empty($sa['project_id'])) {
        logLine('ERROR: ' . FCM_KEY_FILE . ' is not a valid service-account key');
        exit(1);
    }
    return $sa;
}

/**
 * Returns a Google OAuth access token for sending FCM messages.
 * Tokens last an hour, so the result is cached in a private temp file.
 */
function getAccessToken(array $sa): string {
    $cacheFile = sys_get_temp_dir() . '/routrack_fcm_token_' . md5($sa['client_email']) . '.json';
    if (is_readable($cacheFile)) {
        $cached = json_decode(file_get_contents($cacheFile), true);
        if (is_array($cached) && !empty($cached['token'])
                && ($cached['expires'] ?? 0) > time() + 60) {
            return $cached['token'];
        }
    }

    $tokenUri = $sa['token_uri'] ?? 'https://oauth2.googleapis.com/token';
    $now = time();
    $header = base64Url(json_encode(['alg' => 'RS256', 'typ' => 'JWT']));
    $claims = base64Url(json_encode([
        'iss'   => $sa['client_email'],
        'scope' => 'https://www.googleapis.com/auth/firebase.messaging',
        'aud'   => $tokenUri,
        'iat'   => $now,
        'exp'   => $now + 3600,
    ]));
    $signature = '';
    if (!openssl_sign($header . '.' . $claims, $signature, $sa['private_key'], OPENSSL_ALGO_SHA256)) {
        logLine('ERROR: could not sign the token request (openssl_sign failed)');
        exit(1);
    }
    $jwt = $header . '.' . $claims . '.' . base64Url($signature);

    [$status, $body] = httpPost(
        $tokenUri,
        ['Content-Type: application/x-www-form-urlencoded'],
        http_build_query([
            'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer',
            'assertion'  => $jwt,
        ])
    );
    $json = json_decode($body, true);
    if ($status !== 200 || empty($json['access_token'])) {
        logLine('ERROR: Google refused the service-account login (HTTP ' . $status . '): ' . $body);
        exit(1);
    }

    $old = umask(0077);   // cache file readable only by the user running this script
    file_put_contents($cacheFile, json_encode([
        'token'   => $json['access_token'],
        'expires' => $now + (int)($json['expires_in'] ?? 3600),
    ]));
    umask($old);

    return $json['access_token'];
}

/**
 * Sends one DATA-ONLY, HIGH-PRIORITY message. High priority is what allows the app
 * to start its foreground service from the background on Android 12+.
 * Returns [httpStatus, body].
 */
function sendFcm(array $sa, string $accessToken, string $deviceToken, string $action): array {
    $url = 'https://fcm.googleapis.com/v1/projects/' . $sa['project_id'] . '/messages:send';
    $payload = json_encode([
        'message' => [
            'token'   => $deviceToken,
            'data'    => ['action' => $action],
            'android' => [
                'priority' => 'HIGH',
                // If the phone is unreachable, drop the message after 10 minutes
                // rather than delivering a stale restart hours later.
                'ttl'      => '600s',
            ],
        ],
    ]);
    return httpPost(
        $url,
        ['Authorization: Bearer ' . $accessToken, 'Content-Type: application/json'],
        $payload
    );
}

/** True when Firebase says this token no longer belongs to any install. */
function isDeadToken(int $status, string $body): bool {
    return $status === 404
        || strpos($body, 'UNREGISTERED') !== false
        || ($status === 400 && strpos($body, 'registration token') !== false);
}

// ─── MAIN ─────────────────────────────────────────────────────────────────────

$args   = array_slice($argv, 1);
$dryRun = in_array('--dry-run', $args, true);
$conn   = getDbConnection();

// ── Manual test: php fcm_revive.php --test <mobile> [ping|restart_tracking] ──
if (($args[0] ?? '') === '--test') {
    $mobile = $args[1] ?? '';
    $action = $args[2] ?? 'ping';
    if ($mobile === '' || !in_array($action, ['ping', 'restart_tracking'], true)) {
        logLine('Usage: php fcm_revive.php --test <mobile> [ping|restart_tracking]');
        exit(1);
    }
    $stmt = $conn->prepare("SELECT fcm_token FROM user_device_binding WHERE mobile_no = ? LIMIT 1");
    if (!$stmt) {
        logLine('ERROR: ' . $conn->error . ' (has fcm_schema.sql been run?)');
        exit(1);
    }
    $stmt->bind_param("s", $mobile);
    $stmt->execute();
    $row = $stmt->get_result()->fetch_assoc();
    $stmt->close();
    if (!$row || empty($row['fcm_token'])) {
        logLine("No FCM token stored for $mobile — the app has not registered one yet.");
        exit(1);
    }
    $sa = loadServiceAccount();
    [$status, $body] = sendFcm($sa, getAccessToken($sa), $row['fcm_token'], $action);
    logLine("Sent '$action' to $mobile -> HTTP $status");
    logLine($body);
    exit($status === 200 ? 0 : 1);
}

// ── Normal run: find quiet devices ──
$sql = "
    SELECT u.id, u.mobile_no, u.fcm_token, MAX(d." . DATA_TIME_COL . ") AS last_seen
    FROM user_device_binding u
    JOIN " . DATA_TABLE . " d
      ON d." . DATA_MOBILE_COL . " = u.mobile_no
     AND d." . DATA_TIME_COL . " >= NOW() - INTERVAL " . (int)GIVE_UP_AFTER_HOURS . " HOUR
    WHERE u.fcm_token IS NOT NULL AND u.fcm_token <> ''
      AND u.insta_status = 1
      AND u.bind_status = 'ACTIVE'
      AND (u.fcm_last_push_on IS NULL
           OR u.fcm_last_push_on < NOW() - INTERVAL " . (int)REPUSH_EVERY_MINUTES . " MINUTE)
    GROUP BY u.id, u.mobile_no, u.fcm_token
    HAVING last_seen < NOW() - INTERVAL " . (int)QUIET_AFTER_MINUTES . " MINUTE
";
$result = $conn->query($sql);
if (!$result) {
    logLine('ERROR: query failed: ' . $conn->error);
    exit(1);
}

$quiet = $result->fetch_all(MYSQLI_ASSOC);
if (count($quiet) === 0) {
    exit(0);   // nothing to do — stay silent so the cron log stays small
}

if ($dryRun) {
    foreach ($quiet as $row) {
        logLine('WOULD push ' . $row['mobile_no'] . ' (last seen ' . $row['last_seen'] . ')');
    }
    exit(0);
}

$sa          = loadServiceAccount();
$accessToken = getAccessToken($sa);

$markPushed = $conn->prepare("UPDATE user_device_binding SET fcm_last_push_on = NOW() WHERE id = ?");
$clearToken = $conn->prepare("UPDATE user_device_binding SET fcm_token = NULL WHERE id = ?");

foreach ($quiet as $row) {
    [$status, $body] = sendFcm($sa, $accessToken, $row['fcm_token'], 'restart_tracking');

    if ($status === 200) {
        logLine('pushed restart_tracking to ' . $row['mobile_no']
                . ' (last seen ' . $row['last_seen'] . ')');
    } elseif (isDeadToken($status, $body)) {
        // App uninstalled or token rotated — forget it; the app re-registers on next login.
        logLine('token no longer valid for ' . $row['mobile_no'] . ' — cleared');
        if ($clearToken) {
            $clearToken->bind_param("i", $row['id']);
            $clearToken->execute();
        }
        continue;
    } else {
        logLine('FAILED for ' . $row['mobile_no'] . ' (HTTP ' . $status . '): ' . $body);
    }

    // Recorded on success AND on other failures, so one bad device is retried at the
    // normal interval instead of on every cron run.
    if ($markPushed) {
        $markPushed->bind_param("i", $row['id']);
        $markPushed->execute();
    }
}

$conn->close();
