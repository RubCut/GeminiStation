from PIL import Image, ImageDraw, ImageFont, ImageFilter
from math import sin, cos, pi
from pathlib import Path
import zipfile

OUT=Path(__file__).resolve().parent
W=640; CX=CY=320; R=186; FPS=25; N=24
FONT='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
BOLD='/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
font_title=ImageFont.truetype(BOLD,25); font_sub=ImageFont.truetype(FONT,14)
font_centre=ImageFont.truetype(BOLD,20); font_note=ImageFont.truetype(FONT,15)
font_num=ImageFont.truetype(BOLD,12)
BG=(12,13,21); FG=(235,233,248); MUTED=(145,144,166)
blue=(94,217,255); pink=(255,102,191)

def smooth(t):
 t=max(0,min(1,t)); return t*t*(3-2*t)
def fade(t):return smooth(t)
def color(i,center,width,alpha):
 d=abs((i-center+12)%24-12)
 if d>=width:return (0,0,0)
 blend=smooth(min(1,d/max(1,width*.66)))
 energy=fade((width-d)/max(1,width*.72))*alpha
 return tuple(round((blue[k]*(1-blend)+pink[k]*blend)*energy) for k in range(3))
def text_center(draw,xy,text,font,fill):
 draw.text(xy,text,font=font,fill=fill,anchor='mm')

def render(state,center,width,alpha,title,desc):
 im=Image.new('RGB',(W,W),BG)
 d=ImageDraw.Draw(im)
 text_center(d,(320,49),title,font_title,FG)
 text_center(d,(320,80),desc,font_sub,MUTED)
 # Hardware-only schematic: only the 24 nodes are individual light sources.
 d.ellipse((117,117,523,523),fill=(20,21,32),outline=(49,48,66),width=2)
 d.ellipse((151,151,489,489),fill=(10,11,18),outline=(37,37,50),width=2)
 text_center(d,(320,310),'GEMINI',font_centre,(95,94,115))
 text_center(d,(320,337),state,font_sub,(107,106,129))
 positions=[]
 for i in range(N):
  a=pi/2+pi/24+2*pi*i/N
  positions.append((round(CX+R*cos(a)),round(CY+R*sin(a))))
 # Glows mimic light bleed in a conceptual preview, not additional LEDs.
 layer=Image.new('RGBA',(W,W),(0,0,0,0)); glow=ImageDraw.Draw(layer)
 colors=[]
 for i,(x,y) in enumerate(positions):
  c=color(i,center,width,alpha);colors.append(c)
  if max(c)>3:glow.ellipse((x-20,y-20,x+20,y+20),fill=(*c,140))
 im=Image.alpha_composite(im.convert('RGBA'),layer.filter(ImageFilter.GaussianBlur(10)))
 d=ImageDraw.Draw(im)
 for i,((x,y),c) in enumerate(zip(positions,colors)):
  base=(33,33,46)
  if max(c)<3:
   d.ellipse((x-13,y-13,x+13,y+13),fill=base,outline=(60,58,75),width=1)
  else:
   d.ellipse((x-13,y-13,x+13,y+13),fill=tuple(max(base[k],c[k]) for k in range(3)),outline=tuple(min(255,int(v*0.8+30)) for v in c),width=1)
   d.ellipse((x-6,y-8,x+4,y-2),fill=tuple(min(255,int(v*.7+40)) for v in c))
 # Zero-based positions 0..23; the user calls the last front pixel 24.
 for i in range(N):
  a=pi/2+pi/24+2*pi*i/N
  x=round(CX+(R+27)*cos(a)); y=round(CY+(R+27)*sin(a))
  text_center(d,(x,y),'23/24' if i==23 else str(i),font_num,MUTED)
 text_center(d,(320,588),'ПЕРЕД СТАНЦИИ',font_num,(198,193,218))
 text_center(d,(320,613),'24 светодиода · вид сверху',font_note,MUTED)
 return im.convert('RGB')

def activation(t):
 # Light up at the front, accelerate, complete one revolution and brake at front.
 alpha=fade(t/.15)
 p=smooth((t-.10)/(2.08/3))
 return -.5+24*p,3.8,alpha

def listening(t):
 # Purposefully irregular targets; shortest paths and easing avoid teleporting.
 points=[-.5,5,19,9,23,3,14,6,20,11,-.5]
 travel=[.22,.33,.26,.27,.30,.32,.25,.30,.29,.34]
 holds=[.10,.07,.09,.08,.11,.06,.08,.10,.07,.08]
 for n in range(len(travel)):
  a=points[n]; b=points[n+1]
  delta=(b-a+12)%24-12
  if t<travel[n]:return a+delta*smooth(t/travel[n]),3.5,1
  t-=travel[n]
  if t<holds[n]:return b,3.5,1
  t-=holds[n]
 return -.5,3.5,1

def thinking(t):return -.5+24*(t/2.5),3.8,1

def speaking(t):
 # Return from a sample thinking position and keep pulsing at the front.
 if t < .55:
  return 8.0-8.5*smooth(t/.55),3.8,1
 pulse=.5-.5*cos(2*pi*(t-.55)/1.05)
 return -.5,3.0+1.9*pulse,1

jobs=[('01-activation.gif','АКТИВАЦИЯ','Появляется спереди · ускоряется · возвращается','звук активации',.86,activation),
('02-listening.gif','СЛУШАЕТ','Быстрые плавные перемещения по кольцу','ждёт вашу речь',sum([.22,.33,.26,.27,.30,.32,.25,.30,.29,.34])+sum([.10,.07,.09,.08,.11,.06,.08,.10,.07,.08]),listening),
('03-thinking.gif','ДУМАЕТ','Медленное движение по кругу','готовит ответ',2.5,thinking),
('04-speaking.gif','ОТВЕЧАЕТ','Возвращается вперёд · расширяется и сжимается','озвучивает ответ',4.75,speaking)]

# Build one palette per animation to keep static parts stable between frames.
for filename,title,desc,state,duration,func in jobs:
 samples=[]
 for j in range(10):
  p=func(duration*j/10); samples.append(render(state,*p,title,desc).resize((160,160)))
 palette_sheet=Image.new('RGB',(160*5,160*2),BG)
 for i,im in enumerate(samples):palette_sheet.paste(im,((i%5)*160,(i//5)*160))
 palette=palette_sheet.quantize(colors=256,method=Image.Quantize.MEDIANCUT,dither=Image.Dither.NONE)
 count=round(duration*FPS)
 frames=[]
 for i in range(count):
  p=func(i/FPS)
  frames.append(render(state,*p,title,desc).quantize(palette=palette,dither=Image.Dither.NONE))
 frames[0].save(OUT/filename,save_all=True,append_images=frames[1:],duration=round(1000/FPS),loop=0,optimize=False,disposal=2)
 print(filename, 'frames',len(frames),'bytes',(OUT/filename).stat().st_size,flush=True)
print('GIF previews ready')
