<?php
/**
 * register_fcm_token.php — stores a device's Firebase Cloud Messaging token.
 *
 * Called by the Android app (ApiService.registerFcmToken) after login and on service
 * start until it succeeds. The token lets fcm_revive.php push a "restart_tracking"
 * message to a device that has gone quiet.
 *
 * POST (form-urlencoded): mobile_no, android_id, fcm_token
 * Reply (JSON):           {"success":true,"message":"..."} or {"success":false,...}
 *
 * Requires the columns added by fcm_schema.sql.
 */
error_reporting(E_ALL);
ini_set('display_errors', 0);
ini_set('log_errors', 1);

require_once 'config.php';

// Return false on query errors instead of throwing, so every failure below is
// reported as clean JSON rather than an HTTP 500.
mysqli_report(MYSQLI_REPORT_OFF);

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    sendResponse(false, 'POST required');
}

$mobile_no  = isset($_POST['mobile_no'])  ? sanitizeInput($_POST['mobile_no'])  : '';
$android_id = isset($_POST['android_id']) ? sanitizeInput($_POST['android_id']) : '';
$fcm_token  = isset($_POST['fcm_token'])  ? trim($_POST['fcm_token'])           : '';

if ($mobile_no === '' || $android_id === '' || $fcm_token === '') {
    sendResponse(false, 'Missing mobile number, device ID or token.');
}
if (!validateMobile($mobile_no)) {
    sendResponse(false, 'Invalid mobile number format.');
}
// FCM tokens are URL-safe text. Reject anything else rather than storing it.
if (strlen($fcm_token) > 1024 || !preg_match('/^[A-Za-z0-9_:\-\.]+$/', $fcm_token)) {
    sendResponse(false, 'Invalid token format.');
}

$conn = getDbConnection();

// The token is only accepted for the device this mobile number is bound to —
// the same rule verify_activation.php enforces for login.
$stmt = $conn->prepare("
    SELECT id, bind_status
    FROM user_device_binding
    WHERE mobile_no = ? AND android_id = ? AND insta_status = 1
    LIMIT 1
");
if (!$stmt) {
    sendResponse(false, 'Database error (lookup).');
}
$stmt->bind_param("ss", $mobile_no, $android_id);
$stmt->execute();
$user = $stmt->get_result()->fetch_assoc();
$stmt->close();

if (!$user) {
    sendResponse(false, 'Device is not bound to this mobile number.');
}
if ($user['bind_status'] === 'INACTIVE') {
    sendResponse(false, 'Account is deactivated.');
}

// A token identifies one app install. If another account used this phone before,
// remove the token from that row so a push can never go to the wrong user.
$clear = $conn->prepare("
    UPDATE user_device_binding
    SET fcm_token = NULL
    WHERE fcm_token = ? AND id <> ?
");
if ($clear) {
    $clear->bind_param("si", $fcm_token, $user['id']);
    $clear->execute();
    $clear->close();
}

$upd = $conn->prepare("
    UPDATE user_device_binding
    SET fcm_token = ?, fcm_updated_on = NOW()
    WHERE id = ?
");
if (!$upd) {
    // Most likely cause: fcm_schema.sql has not been run yet.
    error_log('register_fcm_token: prepare failed: ' . $conn->error);
    sendResponse(false, 'Database error (update).');
}
$upd->bind_param("si", $fcm_token, $user['id']);
$ok = $upd->execute();
$upd->close();
$conn->close();

if ($ok) {
    sendResponse(true, 'Token registered');
}
sendResponse(false, 'Could not save token.');
