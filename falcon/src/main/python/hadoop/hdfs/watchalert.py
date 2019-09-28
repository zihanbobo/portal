#!/usr/bin/env python
# -*- coding:utf-8 -*-

import sys
import logging
import time
import re
import mimetypes
import urllib
import urllib2
import traceback
import json
import argparse


class Client(object):

    def __init__(self, host, port=80, need_authorize=False):
        self.host = host
        self.port = port
        self.timeout = 10
        self.need_authorize = need_authorize
        self.kid = None
        self.passwd = None
        self.api_expired = 60
        self.ip = self.local_ip()
        self.python_version_is_bigger_than_2_4 = float(sys.version[:3]) > 2.4

    def bind_auth(self, kid, passwd, api_expired=600):
        self.need_authorize = True
        self.kid = kid
        self.passwd = passwd
        self.api_expired = api_expired

    def set_timeout(self, timeout):
        self.timeout = timeout

    def hash(self, request, method='POST'):
        try:
            from hashlib import md5, sha1
        except:
            from md5 import md5
            import sha as sha1
        import hmac
        http_verb = request.get_method()
        if method == 'POST':
            content_md5 = md5(request.data).hexdigest()
            request.add_header('Content-MD5', content_md5)
        else:
            content_md5 = ''
        content_type = request.headers.get('Content-type', '')
        expired = str(time.time() + self.api_expired)
        request.add_header('Expires', expired)
        if self.ip:
            request.add_header('x-sinawatch-ip', self.ip)
        headers = []
        sorted_headers = request.headers.keys()
        sorted_headers.sort(lambda x, y: cmp(x.lower(), y.lower()))
        for key in sorted_headers:
            if re.match('x-sinawatch-', key, re.I) or re.match('x-sina-', key, re.I):
                item = key.lower() + ':' + request.headers[key].strip() + '\n'
                headers.append(item)
        canonicalizedamzheaders = ''.join(headers)
        canonicalizedresource = request.get_selector()
        stringtosign = '\n'.join([http_verb,
                                  content_md5,
                                  content_type,
                                  expired,
                                  canonicalizedamzheaders + canonicalizedresource])
        signature = hmac.new(self.passwd, stringtosign, sha1).digest()
        ssig = signature.encode('base64')[5:15]
        request.add_header('Authorization', 'sinawatch %s:%s' %
                           (self.kid, ssig))

    def get(self, path, queries={}, parse=False):
        url = self.get_url(path)
        queries = self.encode_queries(queries)
        request = urllib2.Request('%s?%s' % (url, queries))
        if self.need_authorize:
            self.hash(request, 'GET')
        if self.python_version_is_bigger_than_2_4:
            response = urllib2.urlopen(request, timeout=self.timeout)
        else:
            # http://stackoverflow.com/questions/2084782/timeout-for-urllib2-urlopen-in-pre-python-2-6-versions
            import socket
            socket.setdefaulttimeout(self.timeout)
            response = urllib2.urlopen(request)
        if parse:
            data, message = self.parse(response.read())
            return data
        else:
            return response.read()

    def post(self, path, data=[], files=[], parse=False):
        url = self.get_url(path)
        if isinstance(data, dict):
            data = data.items()
        content_type, body = self.encode_multipart_formdata(data, files)
        request = urllib2.Request(url, data=body)
        request.add_header('Content-Type', content_type)
        request.add_header('Content-Length', str(len(body)))
        if self.need_authorize:
            self.hash(request)
        if self.python_version_is_bigger_than_2_4:
            response = urllib2.urlopen(request, timeout=self.timeout)
        else:
            import socket
            socket.setdefaulttimeout(self.timeout)
            response = urllib2.urlopen(request)
        if parse:
            data, message = self.parse(response.read())
            return data
        else:
            return response.read()

    def parse(self, response):
        logging.debug('responsed %s' % response)
        try:
            import json
            data = json.loads(response)
            for key in ['code', 'data', 'message']:
                if key not in data:
                    raise Exception('responsed wrong api format.')
        except:
            raise Exception('responsed wrong api format.')
        if data['code']:
            raise Exception(data['message'])
        else:
            return data['data'], data['message']

    def get_content_type(self, filename):
        return mimetypes.guess_type(filename)[0] or 'application/octet-stream'

    def encode_multipart_formdata(self, fields, files):
        """
        fields is a sequence of (name, value) elements for regular form fields.
        files is a sequence of (name, filename, value) elements for data to be uploaded as files
        Return (content_type, body) ready for httplib.HTTP instance
        """
        BOUNDARY = '----------%s' % hex(int(time.time() * 1000))
        CRLF = '\r\n'
        L = []
        for (key, value) in fields:
            L.append('--' + BOUNDARY)
            L.append('Content-Disposition: form-data; name="%s"' % str(key))
            L.append('')
            if isinstance(value, unicode):
                L.append(value.encode('utf-8'))
            else:
                L.append(value)
        for (key, filename, value) in files:
            L.append('--' + BOUNDARY)
            L.append('Content-Disposition: form-data; name="%s"; filename="%s"' %
                     (str(key), str(filename)))
            L.append('Content-Type: %s' % str(self.get_content_type(filename)))
            L.append('Content-Length: %d' % len(value))
            L.append('')
            L.append(value)
        L.append('--' + BOUNDARY + '--')
        L.append('')
        body = CRLF.join(L)
        content_type = 'multipart/form-data; boundary=%s' % BOUNDARY
        return content_type, body

    def encode_queries(self, queries={}, **kwargs):
        queries.update(kwargs)
        args = []
        for k, v in queries.iteritems():
            if isinstance(v, unicode):
                qv = v.encode('utf-8')
            else:
                qv = str(v)
            args.append('%s=%s' % (k, urllib.quote(qv)))
        return '&'.join(args)

    def get_url(self, path):
        if not path.startswith('/'):
            path = '/' + path
        path = urllib.quote(path.encode('utf-8'))
        url = 'http://%s:%s%s' % (self.host, self.port, path)
        return url

    def local_ip(self):
        IFNAMES = ['eth0', 'eth1', 'eth2', 'bce0', 'bce1', 're0', 're1'],
        IFCONFIGS = ['ifconfig 2>&1', '/sbin/ifconfig -a 2>&1',
                     '/usr/sbin/ifconfig -a 2>&1']
        try:
            import socket
            import fcntl
            import struct
            import re
            import os
            for _if in IFNAMES:
                try:
                    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    ip = socket.inet_ntoa(fcntl.ioctl(
                        s.fileno(),
                        0x8915,  # SIOCGIFADDR
                        struct.pack('256s', _if[:15])
                    )[20:24])
                    if ip != '127.0.0.1' and re.match('^[\d]{1,3}\.[\d]{1,3}\.[\d]{1,3}\.[\d]{1,3}$', ip):
                        return ip
                except:
                    pass
            for _cmd in IFCONFIGS:
                try:
                    pipe = os.popen(_cmd)
                    for line in pipe.readlines():
                        m = re.search(
                            'inet (?P<sys>addr:|)(?P<ip>[\d]{1,3}\.[\d]{1,3}\.[\d]{1,3}\.[\d]{1,3})', line)
                        if m and m.group('ip') != '127.0.0.1':
                            return m.group('ip')
                except:
                    pass
        except:
            return None


def usage(return_code=2):
    help = """
Usage %s --sv "monitor" --service "service" --object "object" --subject "subject" --msgto "someone,otherone" [options]
 please go to http://wiki.intra.sina.com.cn/pages/viewpage.action?pageId=7162793 for more details.
 options:
  --kid             kid for your application
                    visit http://wiki.intra.sina.com.cn/pages/viewpage.action?pageId=7162775 for more details
  --passwd          password for your kid
  --sv              sv
  --service         service
  --object          object
  --subject         subject
  --content         content
  --html            html
  --mailto          contacts (split by comma)
  --msgto           contacts (split by comma)
  --ivrto           contacts (split by comma)
  --weiboto         contacts (split by comma)
  --wechatto        contacts (split by comma)
  --pushto          ontacts (split by comma)
  --gmailto         contact groups (split by comma)
  --gmsgto          contact groups (split by comma)
  --givrto          contact groups (split by comma)
  --gweiboto        contact groups (split by comma)
  --gwechatto       contact groups (split by comma)
  --gpushto         contact groups (split by comma)
  --auto_merge      enable/disable auto merge (enable by default)
  --url             change the default url.
  --debug           enable debug logging
  --help            help usage
 # WARNING: It isn't safe to put your kid/passwd on the
 # command line! The recommended strategy is to store
 # your kid/passwd in this scripts owned by, and only readable
 # by you.
""" % sys.argv[0]
    print help
    sys.exit(return_code)
_debug = False


def debug(message, *args):
    if _debug:
        print message % args


def sendAlertToUsers(service, subservice, subject, content, users, weiboto=True, mailto=True, msgto=False):
    if not (service and subservice and subject and users):
        logging.warn(
            "illegal alert msg, service: %s, object: %s, subject: %s, content: %s, users: %s")
        return
    params = dict(
        kid="2012090416",
        passwd="VrcUe70eTvnGIm3wd4g6cLtMlvg7s7",
        # For neiwang
        url="http://iconnect.monitor.sina.com.cn/v1/alert/send",
        # For waiwang
        # url = 'http://connect.monitor.sina.com.cn/v1/alert/send',
        sv="DIP",
        service="",
        object="",
        subject="",
        content="",
        html="",
        mailto="",
        msgto="",
        ivrto="",
        weiboto="",
        wechatto="",
        pushto="",
        gmailto="",
        gmsgto="",
        givrto="",
        gweiboto="",
        gwechatto="",
        gpushto="",
        auto_merge="1",
    )
    params["service"] = service
    params["object"] = subservice
    params["subject"] = subject
    params["content"] = content
    if weiboto:
        params["weiboto"] = users
    if mailto:
        params["mailto"] = users
    if msgto:
        params["msgto"] = users
    matcher = re.match("https?://(?P<hostname>[^/:?]+)(?::(?P<port>\d+))?(?P<path>[^?]*)(?:\?(?P<query>\S+))?",
                       params.pop('url'))
    try:
        client = Client(matcher.group("hostname"), matcher.group("port") or 80)
        client.bind_auth(params.pop("kid"), params.pop("passwd"))
        response = client.post(matcher.group("path"), params)
        if not (response and json.loads(response)["code"] == 0):
            logging.warn("send alert error: %s" % response)
    except Exception:
        logging.warn("set alert error: %s" % traceback.format_exc())


def sendAlertToGroups(service, subservice, subject, content, groups, weiboto=True, mailto=True, msgto=False):
    if not (service and subservice and subject and groups):
        logging.warn(
            "illegal alert msg, service: %s, object: %s, subject: %s, content: %s, groups: %s")
        return
    params = dict(
        kid="2012090416",
        passwd="VrcUe70eTvnGIm3wd4g6cLtMlvg7s7",
        # For neiwang
        url="http://iconnect.monitor.sina.com.cn/v1/alert/send",
        # For waiwang
        # url = 'http://connect.monitor.sina.com.cn/v1/alert/send',
        sv="DIP",
        service="",
        object="",
        subject="",
        content="",
        html="",
        mailto="",
        msgto="",
        ivrto="",
        weiboto="",
        wechatto="",
        pushto="",
        gmailto="",
        gmsgto="",
        givrto="",
        gweiboto="",
        gwechatto="",
        gpushto="",
        auto_merge="1",
    )
    params["service"] = service
    params["object"] = subservice
    params["subject"] = subject
    params["content"] = content
    if weiboto:
        params["gweiboto"] = groups
    if mailto:
        params["gmailto"] = groups
    if msgto:
        params["gmsgto"] = groups
    matcher = re.match("https?://(?P<hostname>[^/:?]+)(?::(?P<port>\d+))?(?P<path>[^?]*)(?:\?(?P<query>\S+))?",
                       params.pop('url'))
    try:
        client = Client(matcher.group("hostname"), matcher.group("port") or 80)
        client.bind_auth(params.pop("kid"), params.pop("passwd"))
        response = client.post(matcher.group("path"), params)
        if not (response and json.loads(response)["code"] == 0):
            logging.warn("send alert error: %s" % response)
    except Exception:
        logging.warn("set alert error: %s" % traceback.format_exc())

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="SinaWatch Alert Service")

    parser.add_argument("-type", "--type", metavar='str', type=str,
                        action="store", required=True, help="user or group")
    parser.add_argument("-service", "--service", type=str,
                        action="store", required=True, help="service")
    parser.add_argument("-subservice", "--subservice", type=str,
                        action="store", required=True, help="subservice")
    parser.add_argument("-subject", "--subject", type=str,
                        action="store", required=True, help="subject")
    parser.add_argument("-content", "--content", type=str,
                        action="store", required=True, help="content")
    parser.add_argument("-receivers", "--receivers", type=str,
                        action="store", required=True, help="users or groups, delimited by ','")
    parser.add_argument("-weibo", "--weibo",
                        action="store_true", required=False, help="send to weibo")
    parser.add_argument("-mail", "--mail",
                        action="store_true", required=False, help="send to mail")
    parser.add_argument("-msg", "--msg",
                        action="store_true", required=False, help="send to msg")

    args = parser.parse_args()

    if args.type == "user":
        sendAlertToUsers(args.service, args.subservice, args.subject,
                         args.content, args.receivers, args.weibo, args.mail, args.msg)
    elif args.type == "group":
        sendAlertToGroups(args.service, args.subservice, args.subject,
                          args.content, args.receivers, args.weibo, args.mail, args.msg)
    else:
        parser.print_help()

