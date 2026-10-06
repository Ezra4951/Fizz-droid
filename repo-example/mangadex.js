// @name MangaDex (sample)
// @type manga
// Synchronous API: use http.get(url) -> string. Oldest chapter first.
// search(q,page) -> [{title,url,cover}]  detail(url) -> {description,chapters:[{name,url}]}
// content(url) -> {type:'images',items:[urls]} | {type:'video',items:[{url}]} | {type:'text',text}
const API = 'https://api.mangadex.org';
const J = u => JSON.parse(http.get(u));
function search(q, page) {
  const r = J(API + '/manga?title=' + encodeURIComponent(q) + '&limit=20&includes[]=cover_art');
  return r.data.map(m => {
    const c = m.relationships.find(x => x.type === 'cover_art');
    return { title: Object.values(m.attributes.title)[0], url: m.id,
      cover: c ? 'https://uploads.mangadex.org/covers/' + m.id + '/' + c.attributes.fileName + '.256.jpg' : '' };
  });
}
function detail(id) {
  const m = J(API + '/manga/' + id).data;
  const f = J(API + '/manga/' + id + '/feed?translatedLanguage[]=en&order[chapter]=asc&limit=100');
  return { description: (m.attributes.description || {}).en || '',
    chapters: f.data.map(c => ({ name: 'Ch. ' + (c.attributes.chapter || '?') + (c.attributes.title ? ' - ' + c.attributes.title : ''), url: c.id })) };
}
function content(id) {
  const r = J(API + '/at-home/server/' + id);
  return { type: 'images', items: r.chapter.data.map(f => r.baseUrl + '/data/' + r.chapter.hash + '/' + f) };
}
