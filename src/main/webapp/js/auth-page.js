'use strict';
const isRegister = document.body.dataset.page === 'register';
const copy = {
 en: {title: isRegister ? 'Create your account' : 'Welcome back', subtitle: isRegister ? 'Start managing and reducing food waste intelligently.' : 'Sign in to continue to your food waste dashboard.', fullName:'Full Name',email:'Email Address',password:'Password',confirm:'Confirm Password',submit:isRegister?'Create Account':'Sign In',foot:isRegister?'Already have an account?':'Don’t have an account?',link:isRegister?'Sign In':'Create Account',show:'Show',hide:'Hide',hint:'Use at least 8 characters with uppercase, lowercase, a number and a symbol.',busy:'Please wait…',error:'Unable to complete the request. Please try again.',invalid:'Invalid email or password.',duplicate:'An account with this email already exists.',mismatch:'Passwords do not match.',success:'Account created successfully.',required:'Please complete all required fields.',weak:'Please use a password that meets the requirements.'},
 mm: {title:isRegister?'အကောင့်အသစ်ဖွင့်ရန်':'ပြန်လည်ကြိုဆိုပါသည်',subtitle:isRegister?'အစားအသောက်အလေအလွင့်ကို ထိရောက်စွာ စီမံလျှော့ချရန် စတင်လိုက်ပါ။':'ဆက်လက်အသုံးပြုရန် ဝင်ရောက်ပါ',fullName:'အမည်အပြည့်အစုံ',email:'အီးမေးလ်လိပ်စာ',password:'စကားဝှက်',confirm:'စကားဝှက်အတည်ပြုရန်',submit:isRegister?'အကောင့်ဖွင့်မည်':'ဝင်ရောက်မည်',foot:isRegister?'အကောင့်ရှိပြီးသားလား?':'အကောင့်မရှိသေးဘူးလား?',link:isRegister?'ဝင်ရောက်မည်':'အကောင့်အသစ်ဖွင့်ရန်',show:'ပြရန်',hide:'ဖျောက်ရန်',hint:'စာလုံးကြီး၊ စာလုံးသေး၊ ဂဏန်းနှင့် သင်္ကေတ ပါဝင်သော အနည်းဆုံး ၈ လုံးကို အသုံးပြုပါ။',busy:'ခဏစောင့်ပါ…',error:'တောင်းဆိုမှုကို မဆောင်ရွက်နိုင်ပါ။ ထပ်မံကြိုးစားပါ။',invalid:'အီးမေးလ် သို့မဟုတ် စကားဝှက် မမှန်ပါ။',duplicate:'ဤအီးမေးလ်ဖြင့် အကောင့်ရှိပြီးဖြစ်ပါသည်။',mismatch:'စကားဝှက်များ မတူညီပါ။',success:'အကောင့်ကို အောင်မြင်စွာ ဖန်တီးပြီးပါပြီ။',required:'လိုအပ်သော အချက်အလက်များကို ဖြည့်စွက်ပါ။',weak:'သတ်မှတ်ချက်များနှင့် ကိုက်ညီသော စကားဝှက်ကို အသုံးပြုပါ။'}
};
let language = localStorage.getItem('language') === 'mm' ? 'mm' : 'en';
let messageKey = null;
const form = document.querySelector('form');
const message = document.getElementById('message');
const submit = document.getElementById('submit');
function translate(){
 document.documentElement.lang = language === 'mm' ? 'my' : 'en';
 document.title = copy[language].title + ' | FoodWaste AI';
 document.querySelectorAll('[data-text]').forEach(el=>el.textContent=copy[language][el.dataset.text]);
 document.querySelectorAll('.toggle').forEach(el=>el.textContent=copy[language][document.getElementById(el.dataset.target).type==='password'?'show':'hide']);
 if(messageKey) message.textContent=copy[language][messageKey];
}
document.getElementById('language').value=language;
document.getElementById('language').addEventListener('change',event=>{language=event.target.value;localStorage.setItem('language',language);translate();});
document.querySelectorAll('.toggle').forEach(button=>button.addEventListener('click',()=>{const input=document.getElementById(button.dataset.target);input.type=input.type==='password'?'text':'password';button.setAttribute('aria-pressed',String(input.type==='text'));translate();}));
function showMessage(key,success=false){messageKey=key;message.hidden=false;message.classList.toggle('success',success);message.textContent=copy[language][key];message.focus();}
form.addEventListener('submit',async event=>{
 event.preventDefault();message.hidden=true;messageKey=null;
 const payload={email:form.email.value.trim().toLowerCase(),password:form.password.value};
 if(!form.checkValidity()){showMessage('required');return;}
 if(isRegister){
  payload.fullName=form.fullName.value.trim();payload.confirmPassword=form.confirmPassword.value;
  if(!payload.fullName){showMessage('required');return;}
  if(payload.password!==payload.confirmPassword){showMessage('mismatch');return;}
  if(payload.password.length<8||!/[A-Z]/.test(payload.password)||!/[a-z]/.test(payload.password)||!/[0-9]/.test(payload.password)||!/[\W_]/.test(payload.password)){showMessage('weak');return;}
 }
 submit.disabled=true;submit.textContent=copy[language].busy;
 try{
  const response=await fetch('/api/auth/'+(isRegister?'register':'login'),{method:'POST',headers:{'Content-Type':'application/json'},credentials:'same-origin',body:JSON.stringify(payload)});
  const result=await response.json();
  if(!response.ok||!result.success){showMessage(response.status===409?'duplicate':response.status===401?'invalid':'error');return;}
  if(isRegister){form.reset();showMessage('success',true);document.getElementById('alternate').focus();}
  else location.assign('/dashboard.html');
 }catch{showMessage('error');}finally{submit.disabled=false;submit.textContent=copy[language].submit;}
});
translate();
