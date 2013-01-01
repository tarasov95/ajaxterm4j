<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<html>
<head>
	<title>Ajaxterm</title>
	<meta http-equiv="content-type" content="text/html; charset=UTF-8"/>
	<link rel="stylesheet" type="text/css" href="ajaxterm.css"/>
	<script type="text/javascript" src="ajaxterm.js"></script>
</head>
<body style="background-color:#888">
    <div id="term" class="ajaxterm"></div>
    <script type="text/javascript">
        window.onload=function() {
            t=new ajaxterm.Terminal("term",{width:80,height:25,endpoint:"./u"});
        };
   	</script>
</body>
</html>