<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Logs Viewer</title>
    <link href="https://getbootstrap.com/docs/5.3/dist/css/bootstrap.min.css" rel="stylesheet">
</head>
<body class="bg-light">
<div class="container-fluid p-3 bg-white rounded-lg shadow-sm">
    <#include "*/header.ftl">
    <div class="row">
        <div class="col-md-6">
            <h1 class="display-4 font-weight-bold">Trailblaze Recording</h1>
        </div>
    </div>
    <hr/>
    <br/>
    <div class="row">
        <div class="col">
            <pre>${kotlinCode}</pre>
            <button onclick="copyTextToClipboard(window.kotlinCode)">Copy Kotlin</button>
        </div>
    </div>
    <br/>
</div>
<script src="https://getbootstrap.com/docs/5.3/dist/js/bootstrap.bundle.min.js"></script>

<div class="toast-container position-fixed bottom-0 end-0 p-3" style="z-index: 11">
    <div id="copyToast" class="toast" role="alert" aria-live="assertive" aria-atomic="true" data-bs-delay="2000">
        <div class="toast-header">
            <strong class="me-auto">Notification</strong>
            <button type="button" class="btn-close" data-bs-dismiss="toast" aria-label="Close"></button>
        </div>
        <div class="toast-body">
            Copied to clipboard!
        </div>
    </div>
</div>
<script>
    window.kotlinCode = `${kotlinCode}`

    function copyTextToClipboard(textContent) {
        const textarea = document.createElement('textarea');
        textarea.value = textContent;
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        document.body.removeChild(textarea);
        var toastEl = document.getElementById('copyToast');
        var toast = new bootstrap.Toast(toastEl);
        toast.show();
    }

    function copyToClipboard(element) {
        copyTextToClipboard(element.textContent)
    }
</script>
</body>
</html>
