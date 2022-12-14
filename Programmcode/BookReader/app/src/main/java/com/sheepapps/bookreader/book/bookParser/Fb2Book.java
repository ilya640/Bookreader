package com.sheepapps.bookreader.book.bookParser;

import android.util.Log;

import com.sheepapps.bookreader.book.common.Base64InputStream;
import com.sheepapps.bookreader.book.common.ByteCharSequence;
import com.sheepapps.bookreader.book.common.Pair;
import com.sheepapps.bookreader.book.common.XmlReader;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Fb2Book extends BaseBook {
	private final String mFileName;

	public Fb2Book(String fileName) {
		mTitle = fileName;
		mFileName = fileName;
	}

	@Override
	public boolean init(String cachePath) {
		if (mInited)
			return true;
		
		super.init(cachePath);

		long start = System.currentTimeMillis();

		try {
			InputStream stream = null;
			ZipFile zipFile = null;
			long size = 0;

			try {
				if (mFileName.toLowerCase().endsWith(".zip")) {
					zipFile = new ZipFile(mFileName);

					ZipEntry entry;
					Enumeration<? extends ZipEntry> en = zipFile.entries();
					while (en.hasMoreElements()) {
						entry = en.nextElement();
						size = entry.getSize();
						if (size > 0) {
							stream = new BufferedInputStream(zipFile.getInputStream(entry), 0x1000);
							break;
						}
					}

				} else {
					File file = new File(mFileName);
					size = file.length();
					stream = new FileInputStream(file);
				}

				XmlReader xmlReader = new XmlReader(stream, (int)size);
				
				stream.close();
				stream = null;
				
				if (zipFile != null) {
					zipFile.close();
					zipFile = null;
				}
	
				XmlBookParser bookParser = new XmlBookParser();
				bookParser.parse(xmlReader);
				
				BookData data = bookParser.bake();

				xmlReader.clean();
				
				String [] tags = data.getTags();
				BookLine[] lines = data.getLines(); 
				
				HashMap<String, Long> hashTags = new HashMap<String, Long>(tags.length);
				
				for (int i = 0; i < tags.length; i++) {
					hashTags.put(tags[i], 1l << i);
				}
				
				Long fictionBookMask = hashTags.get("fictionbook");
				Long binaryMask = hashTags.get("binary");
				Long bookTitleMask = hashTags.get("book-title");
				Long coverPageMask = hashTags.get("coverpage");
				Long imageMask = hashTags.get("image");
				Long sectionMask = hashTags.get("section");
				Long bodyMask = hashTags.get("body");
				Long titleMask = hashTags.get("title");	
				Long pMask = hashTags.get("p");
				
				if (bodyMask == null || sectionMask == null) {
					Log.e("TextReader", "No <body> and <section> tags in FB2 file!");
					return false;
				}
				
				long parseStart = System.currentTimeMillis();
				
				String title = "";
				int imageBytes = 0;
				int bookEnd = 0;
				int bookStart = 0;
				boolean notes = false;
				LinkedList<BookLine> noteLines = null;
				
				for (int i = 0; i < lines.length; i++) {
					BookLine line = lines[i];
					long mask = line.getTagMask();
					
					if (bookTitleMask != null && (mask & bookTitleMask) != 0) {
						title += line.getText();
						continue;
					}
					
					if (coverPageMask != null && (mask & (coverPageMask | imageMask)) == (coverPageMask | imageMask)) {
						mask |= bodyMask;
						line.setTagMask(mask);
						lines[i+1].setTagMask(fictionBookMask | bodyMask | sectionMask); // hacky, but works
						continue;
					}
					
					if (binaryMask != null && (mask & binaryMask) != 0) {
						String id = line.getAttribute("id");
						String contentType = line.getAttribute("content-type");
						
						if (id != null && contentType != null && contentType.startsWith("image")) {
							ByteCharSequence bytes = ((ByteCharSequence)line.getText());
							
							byte [] image = Base64InputStream.processBase64(bytes.getBytes(), bytes.getOffset(), bytes.length());
							try {
								ImageData imageData = new ImageData(id, image, 0, image.length);
								imageData.init(cachePath);
								mImages.add(imageData);
							}
							catch(Exception ex) {
								Log.w("TextReader", "Failed to read image data " + ex);
							}
							
							imageBytes += bytes.length();
						}							
						line.setText(null);
						line.setAttributes(null);
					}
					
					if (!line.isPart() && (mask & (bodyMask | sectionMask)) == bodyMask) {
						String name = line.getAttribute("name");
						if (name != null) {
							Log.d("TextReader", "Got body name " + name);
							
							if (name.equals("notes"))
								notes = true;
						}
					}
					
					if (notes) {
						if ((mask & (bodyMask | sectionMask)) == (bodyMask | sectionMask)) {
							String id = line.getAttribute("id");
							
							if (id != null) {
								if (!mNotes.containsKey(id))
									mNotes.put(id, noteLines = new LinkedList<BookLine>());
								
							} else if (noteLines != null && (mask & titleMask) == 0)
								noteLines.add(line);
						}
					} else {
						if ((mask & (sectionMask | titleMask | pMask)) == (sectionMask | titleMask | pMask) && line.getText() != null) {
							mChapters.add(new Pair<Long,String>((long)line.getPosition(), line.getText().toString()));
						}
						
						if ((mask & bodyMask) != 0) {
							bookEnd = i == lines.length - 1 ? line.getPosition() : lines[i+1].getPosition();
							if (bookStart == 0)
								bookStart = line.getPosition();
						}
					}
					line.optimize();
				}
				
				if (title.length() > 0)
					mTitle = title;
			
				Log.d("TextReader", "Images use " + imageBytes + " bytes");
							
				mChapters.add(0, new Pair<Long,String>(0l, title.length() > 0 ? title : "Title Page"));
				if (mChapters.size() == 1)
					mChapters.add(new Pair<Long,String>((long)bookEnd, ""));

				//android.os.Debug.stopMethodTracing();

				//System.gc();
				Log.d("TextReader", "Content parsing took " + (System.currentTimeMillis() - parseStart));
				
				
				checkLanguage(data);
				mReader = new Fb2BookReader(data, mTitle);
				mReader.setMaxSize(bookEnd - bookStart);
				mReader.setBookStart(bookStart);
				Log.d("TextReader", "Book end: " + bookEnd + ", start " + bookStart);
				
				/*
				List<ParagraphData> paragraphs = new ArrayList<ParagraphData>(lines.length);
				ParagraphData paragraph = null;
				
				for(int i=0;i<notesPos;i++)
				{
					BookLine line = lines[i];
					if ((line.getTagMask() & bodyMask) != 0)
					{
						if ((line.getTagMask() & imageMask) == imageMask)
						{
							paragraph = new ParagraphData(500);
							paragraphs.add(paragraph); // TODO: ImageData lookup
						} else
						{
							if (line.getText() != null && line.getText().length() > 0)
							{
								int flag = ((Fb2BookReader)m_reader).retrieveFlags(line.getTagMask());
								int chars = line.getText().length();
								
								float modifier = 1.0f;
								if ((flag & BaseBookReader.HEADER) != 0)
									modifier = 1.5f;
								
								ParagraphData newParagraph = new ParagraphData(chars, modifier, (flag & (BaseBookReader.NEW_PAGE | BaseBookReader.NO_NEW_PAGE)) == BaseBookReader.NEW_PAGE);
								
								if ((flag & BaseBookReader.NEW_LINE) != 0 || paragraph == null || !paragraph.addParagraph(newParagraph))
								{
									paragraph = newParagraph;
									paragraphs.add(paragraph);
								}
								
								//paragraphs.add(new ParagraphData(line.getText().length(), 1.0f));
							}
						}
					}
				}
				
				data.setParagraphs(paragraphs);*/
				
				//Log.d("TextReader", "Book have " + paragraphs.size() + " paragraphs");
				//android.os.Debug.stopMethodTracing();
			}
			finally {
				if (zipFile != null)
					zipFile.close();
				if (stream != null)
					stream.close();
			}
		}
		catch (Exception ex) {
			Log.e("TextReader", "FB data retrieve failed: " + ex);
			ex.printStackTrace();
			return false;
		}

		Log.d("TextReader", "Initing readers took " + (System.currentTimeMillis() - start));
		return true;
	}
}
