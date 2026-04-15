import os

file_path = 'app/src/main/java/com/nuvio/tv/ui/screens/home/StreamingPlatformCarousel.kt'

with open(file_path, 'r') as f:
    content = f.read()

# Swap the package declaration
content = content.replace('package com.nuvio.tv', 'package com.nuviodebug')

# Swap the explicit imports for your custom locals
content = content.replace('com.nuvio.tv.R', 'com.nuviodebug.R')
content = content.replace('com.nuvio.tv.LocalContentFocusRequester', 'com.nuviodebug.LocalContentFocusRequester')
content = content.replace('com.nuvio.tv.LocalSidebarExpanded', 'com.nuviodebug.LocalSidebarExpanded')

with open(file_path, 'w') as f:
    f.write(content)

print("Package names updated to com.nuviodebug.")
