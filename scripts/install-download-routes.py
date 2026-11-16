#!/usr/bin/env python3
"""Print a targeted Caddyfile patch; provisioning backup/validate/reload stays explicit."""
import argparse
from pathlib import Path


def patch(text, snippet):
    for host, channel in [('47-129-170-16.sslip.io', 'debug'), ('yszn.nlplayer.org', 'production')]:
        start = text.index(host+' {')
        end = start + len(host+' {')
        depth = 1
        while depth:
            if text[end] == '{': depth += 1
            elif text[end] == '}': depth -= 1
            end += 1
        block = text[start:end]
        if 'handle @downloadPage' in block:
            raise ValueError('download routes already installed; review before editing')
        if channel == 'debug':
            old = '\t\troot * /opt/dngmeng/ota\n\t\trewrite * /debug-v26.json'
            if old not in block: raise ValueError('unexpected legacy Debug route; review current config')
            block = block.replace(old, '\t\troot * /opt/dngmeng/downloads/current\n\t\trewrite * /debug.json', 1)
        else:
            if 'handle /api/v1/app-update' in block: raise ValueError('production override exists; review current config')
            update = '\n\thandle /api/v1/app-update {\n\t\troot * /opt/dngmeng/downloads/current\n\t\trewrite * /production.json\n\t\theader Content-Type application/json\n\t\theader Cache-Control "no-store"\n\t\tfile_server\n\t}\n'
            block = block.replace(host+' {', host+' {'+update, 1)
        block = block.replace(host+' {', host+' {\n'+snippet, 1)
        text = text[:start]+block+text[end:]
    return text


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('config', type=Path)
    parser.add_argument('snippet', type=Path)
    args = parser.parse_args()
    print(patch(args.config.read_text(), args.snippet.read_text()), end='')
