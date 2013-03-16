package com.es.eclipse.externalbuilder.builder;

import java.io.StringWriter;
import java.util.Stack;

import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;

public class SimpleXmlWriter {
    XMLEventFactory mEventFactory;
    XMLEventWriter mEventWriter;
    final XMLEvent ENDLINE;
    StringWriter mStringWriter;
    boolean mDocumentOpen;

    Stack<String> mTags = new Stack<String>();
    boolean mElementOpen = false;

    //Add a element
    public SimpleXmlWriter element( String tag ){
        if( mElementOpen ){
            closeTag();
            addXmlEvent(ENDLINE);
        }
        startTag(tag);
        mElementOpen = true;
        return this;
    }

    public SimpleXmlWriter startChildren(){
        mElementOpen = false;
        addXmlEvent(ENDLINE);
        return this;
    }

    void addXmlEvent( XMLEvent event ){
        try{
            mEventWriter.add( event );
        }
        catch(XMLStreamException e){
            throw new RuntimeException(e);
        }
    }

    void startTag(String tag){
        addXmlEvent( mEventFactory.createStartElement( "", "", mTags.push(tag) ) );
    }

    void closeTag(){
        addXmlEvent( mEventFactory.createEndElement( "", "", mTags.pop() ) );
    }

    public SimpleXmlWriter endChildren(){
        addXmlEvent(ENDLINE);
        closeTag();
        mElementOpen = false;
        return this;
    }

    public SimpleXmlWriter text( String text ){
        addXmlEvent( mEventFactory.createCharacters( text ) );
        closeTag();
        addXmlEvent( ENDLINE );

        mElementOpen = false;
        return this;
    }

    private SimpleXmlWriter(){
        mEventFactory = XMLEventFactory.newInstance();
        XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();
        mStringWriter = new StringWriter();
        mDocumentOpen = true;
        mElementOpen = false;
        try{
            mEventWriter = outputFactory.createXMLEventWriter( mStringWriter );
        }
        catch(XMLStreamException e){
            throw new RuntimeException(e);
        }
        ENDLINE = mEventFactory.createDTD("\n");
        addXmlEvent( mEventFactory.createStartDocument() );
        addXmlEvent( ENDLINE );
    }

    static SimpleXmlWriter newInstance(){
        return new SimpleXmlWriter();
    }

    public String toXml(){
        if( mElementOpen ){
            while( !mTags.empty() ){
                closeTag();
            }
            mElementOpen = false;
        }

        if( mDocumentOpen ){
            try{
                mEventWriter.add( mEventFactory.createEndDocument() );
            }
            catch(XMLStreamException e){
                throw new RuntimeException(e);
            }

            mDocumentOpen = false;
        }
        return mStringWriter.toString();
    }
}
