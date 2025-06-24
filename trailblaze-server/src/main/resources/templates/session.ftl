<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Logs Viewer</title>
    <link href="https://getbootstrap.com/docs/5.3/dist/css/bootstrap.min.css" rel="stylesheet">
    <script type="text/javascript">
        drawScreenshotCircle = function (log_index, deviceWidth, deviceHeight, clickX, clickY) {
            document.addEventListener('DOMContentLoaded', function () {
                var img = document.getElementById('screenshot-' + log_index);
                if (img.complete) { // Ensure image is loaded
                    createCircle(img);
                } else {
                    img.onload = function () {
                        createCircle(img);
                    };
                }

                function createCircle(img) {
                    let realImageWidth = img.naturalWidth;
                    let realImageHeight = img.naturalHeight;
                    let cssDisplayedWidth = img.clientWidth;
                    let cssDisplayedHeight = img.clientHeight;

                    console.log("Natural Size: " + realImageWidth + "x" + realImageHeight);
                    console.log("Displayed Size: " + cssDisplayedWidth + "x" + cssDisplayedHeight);
                    console.log("Device Size: " + deviceWidth + "x" + deviceHeight);

                    let deviceToScreenshotScaleX = deviceWidth / realImageWidth;
                    let deviceToScreenshotScaleY = deviceHeight / realImageHeight;
                    console.log("deviceToScreenshotScaleY : " + deviceToScreenshotScaleX + "x" + deviceToScreenshotScaleY);

                    let scaleX = cssDisplayedWidth / realImageWidth;
                    let scaleY = cssDisplayedHeight / realImageHeight;
                    console.log("scaleX : " + scaleX + "x" + scaleY);

                    let circleX = clickX * scaleX / deviceToScreenshotScaleX;
                    let circleY = clickY * scaleY / deviceToScreenshotScaleY;

                    let radius = 20;
                    let borderWidth = 4;
                    let offset = radius / 2;

                    var circle = document.createElement('div');
                    circle.style.position = 'absolute';
                    circle.style.left = (circleX - offset) + 'px';
                    circle.style.top = (circleY - offset) + 'px';
                    circle.style.width = radius + 'px';
                    circle.style.height = radius + 'px';
                    circle.style.backgroundColor = 'red';
                    circle.style.borderRadius = '50%';
                    circle.style.border = borderWidth + 'px solid white';
                    img.parentElement.appendChild(circle);
                }

                // Recalculate and redraw the circle on window resize
                window.addEventListener('resize', function () {
                    const existingCircle = img.parentElement.querySelector('div');
                    if (existingCircle) {
                        existingCircle.remove();
                    }
                    createCircle(img);
                });
            })
        };
    </script>
</head>
<body class="bg-light">
<div class="container-fluid p-3 bg-white rounded-lg shadow-sm">
    <#include "header.ftl">
    <div class="row">
        <div class="col-md-6">
            <h1 class="display-4 font-weight-bold">Trailblaze Logs</h1>
        </div>
        <div class="col-md-6">
            <a href="/recording/json/${session}">
                <button type="button" class="btn btn-primary me-3">
                    JSON and Kotlin Recording
                </button>
            </a>
            <span> </span>
            <a href="/recording/maestro/${session}">
                <button type="button" class="btn btn-primary me-3">
                    Maestro YAML Recording
                </button>
            </a>
            <a href="/recording/trailblaze/${session}">
                <button type="button" class="btn btn-primary me-3">
                    Trailblaze YAML Recording
                </button>
            </a>
            <span> </span>
            <a href="/llm/${session}">
                <button type="button" class="btn btn-primary me-3">
                    LLM Messages
                </button>
            </a>
            <span> </span>
        </div>
    </div>
    <#if status??>
        <div class="row">
            <div class="col">
                <h2 class="display-6">
                    <span class="font-weight-bold">${statusMessage}</span>
                    <#if inProgress == true>
                        <div class="spinner-border" role="status">
                            <span class="sr-only"></span>
                        </div>
                        <span> Elapsed Time: <span id="elapsed_time"></span> seconds</span>
                    </#if>
                </h2>
                <h4>Prompt</h4>
                <pre>${status.prompt}</pre>
            </div>
        </div>
    </#if>
    <div class="album py-5">
        <div class="row row-cols-1 row-cols-sm-3 row-cols-md-4 row-cols-lg-6">
            <#if logs?size == 0>
                <p>No logs available for Session "${session}"</p>
            </#if>
            <#list logs as log>
                <#include "log_card.ftl">
            </#list>
        </div>
    </div>
    <br/>
    <br/>
    <#if llmUsageSummary??>
        <h5>Llm Usage Summary</h5>
        <pre>${llmUsageSummary}</pre>
    </#if>
</div>
<script src="https://getbootstrap.com/docs/5.3/dist/js/bootstrap.min.js"></script>
<script type="text/javascript">
    document.addEventListener("DOMContentLoaded", function () {
        const id = "${session?json_string}";
        const socket = new WebSocket("ws://" + window.location.host + "/updates?id=" + id);

        socket.addEventListener('message', function (event) {
            console.log('Update received, reloading page...');
            window.location.reload();
        });

        socket.addEventListener('error', function (error) {
            console.error('WebSocket error:', error);
        });

        socket.addEventListener('close', function (event) {
            console.log('WebSocket closed:', event);
        });
    });
</script>
<#if status??>
    <script type="text/javascript">
        const startTimeMs = ${status.taskStartTime?c};

        const displayElapsedSeconds = () => {
            const nowMs = Date.now();
            const elapsedMs = nowMs - startTimeMs;
            const elapsedSeconds = Math.floor(elapsedMs / 1000);

            const elapsedTimeElement = document.getElementById('elapsed_time');
            if (elapsedTimeElement) {
                elapsedTimeElement.textContent = elapsedSeconds.toString();
            }
        };

        displayElapsedSeconds();

        // Update every second
        setInterval(displayElapsedSeconds, 1000);
    </script>
</#if>
</body>
</html>
