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
    <div class="row">
        <div class="col-md-6">
            <h1 class="display-4 font-weight-bold">Trailblaze Sessions</h1>
        </div>
    </div>
    <ul>
        <#list sessions as session>
            <li>
                <h4><a href="/session/${session}">${session}</a></h4>
            </li>
        </#list>
    </ul>
    <br/>
    <#if sessions?size != 0>
        <br/>
        <div class="row">
            <div class="col-md-10"></div>
            <div class="col-md-2 text-align-end">
                <form class="form align-self-md-end" action="/logs/delete" method="post">
                    <input class="btn btn-secondary" type="submit" value="Delete All Logs">
                </form>
            </div>
        </div>
    </#if>
</div>
<script src="https://getbootstrap.com/docs/5.3/dist/js/bootstrap.min.js"></script>
</body>
</html>
