import asyncio,json
from playwright.async_api import async_playwright
JS='''()=>{const c={},f={};let t=document.body.innerText;document.querySelectorAll('*').forEach(e=>{const s=getComputedStyle(e);[s.backgroundColor,s.color,s.borderTopColor].forEach(x=>c[x]=(c[x]||0)+1);f[s.fontFamily+' '+s.fontSize+' '+s.fontWeight]=(f[s.fontFamily+' '+s.fontSize+' '+s.fontWeight]||0)+1});
return {w:innerWidth,h:document.documentElement.scrollHeight,text:t.slice(0,1500),colors:Object.entries(c).sort((a,b)=>b[1]-a[1]).slice(0,12),fonts:Object.entries(f).sort((a,b)=>b[1]-a[1]).slice(0,6)}}'''
async def main():
  async with async_playwright() as p:
    b=await p.chromium.launch(executable_path='/usr/bin/google-chrome',args=['--no-sandbox'])
    pg=await b.new_page(viewport={'width':1600,'height':1000},device_scale_factor=2)
    await pg.goto('file:///workspace/gcp-app/design-user-1012/design.html')
    await pg.wait_for_timeout(8000)
    secs=await pg.query_selector_all('section')
    for i,s in enumerate(secs):
      t=(await s.inner_text()).strip().split('\n')[0]
      await s.scroll_into_view_if_needed(); await pg.wait_for_timeout(800)
      name=f"png/{i+1:02d}-"+t.split(' ',1)[1].replace(' ','').replace('·','-').replace('(','_').replace(')','')+'.png'
      await s.screenshot(path=name); print('==',name)
      fr=await (await s.query_selector('iframe')).content_frame()
      try: print(json.dumps(await fr.evaluate(JS),ensure_ascii=False))
      except Exception as e: print('ERR',e)
    await b.close()
asyncio.run(main())
